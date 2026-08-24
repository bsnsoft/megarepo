package de.bsnsoft.megarepo.format.nuget.v2;

import de.bsnsoft.megarepo.core.format.FormatResponse;
import de.bsnsoft.megarepo.core.format.FormatResponse.ContentResponse;
import de.bsnsoft.megarepo.core.format.FormatResponse.NotFoundResponse;
import de.bsnsoft.megarepo.core.repository.RepositoryConfig;
import de.bsnsoft.megarepo.database.entity.AssetEntity;
import de.bsnsoft.megarepo.database.entity.ComponentEntity;
import de.bsnsoft.megarepo.database.repository.AssetJpaRepository;
import de.bsnsoft.megarepo.database.repository.ComponentJpaRepository;
import de.bsnsoft.megarepo.format.nuget.naming.NugetNames;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * NuGet V2 (OData) read support for hosted repositories.
 *
 * <p>The legacy NuGet V2 protocol is an OData v2 service that returns Atom XML.
 * Older clients (nuget.exe with a V2 source, some CI tooling, Visual Studio
 * package sources configured with a {@code .../api/v2} URL) speak it. This
 * generator implements the read endpoints those clients actually use against a
 * hosted feed:
 *
 * <pre>
 *   GET {feed}/$metadata                       OData EDMX schema
 *   GET {feed}/FindPackagesById()?id='X'       all versions of a package (Atom feed)
 *   GET {feed}/Packages(Id='X',Version='Y')    a single package version (Atom entry)
 *   GET {feed}/Search()?searchTerm='X'         search (Atom feed)
 * </pre>
 *
 * <p>Package <em>content</em> is served by the existing V3 flat-container
 * download path; the Atom entries simply point their {@code <content src>} at it,
 * so a single stored {@code .nupkg} serves both protocols. Push is shared with
 * V3 ({@code PUT {feed}/api/v2/package}) — only the read/metadata surface differs.
 *
 * <p>This is intentionally a hosted-only compatibility layer. Proxying a remote
 * V2 feed and the full OData query grammar ({@code $filter}/{@code $orderby}/…)
 * are out of scope; see the package docs for the exact boundary.
 */
@Component
public class NugetV2FeedGenerator {

    private static final String ATOM_CONTENT_TYPE = "application/atom+xml;type=feed;charset=utf-8";
    private static final String ENTRY_CONTENT_TYPE = "application/atom+xml;type=entry;charset=utf-8";
    private static final String METADATA_CONTENT_TYPE = "application/xml;charset=utf-8";

    private final ComponentJpaRepository componentRepository;
    private final AssetJpaRepository assetRepository;

    public NugetV2FeedGenerator(
            ComponentJpaRepository componentRepository, AssetJpaRepository assetRepository) {
        this.componentRepository = componentRepository;
        this.assetRepository = assetRepository;
    }

    /** {@code GET {feed}/$metadata} — the OData EDMX schema describing the Package entity. */
    public FormatResponse metadata() {
        String edmx = """
                <?xml version="1.0" encoding="utf-8"?>
                <edmx:Edmx Version="1.0" xmlns:edmx="http://schemas.microsoft.com/ado/2007/06/edmx">
                  <edmx:DataServices xmlns:m="http://schemas.microsoft.com/ado/2007/08/dataservices/metadata" m:DataServiceVersion="2.0">
                    <Schema Namespace="NuGetGallery" xmlns="http://schemas.microsoft.com/ado/2006/04/edm">
                      <EntityType Name="V2FeedPackage" m:HasStream="true">
                        <Key><PropertyRef Name="Id"/><PropertyRef Name="Version"/></Key>
                        <Property Name="Id" Type="Edm.String" Nullable="false"/>
                        <Property Name="Version" Type="Edm.String" Nullable="false"/>
                        <Property Name="NormalizedVersion" Type="Edm.String" Nullable="true"/>
                        <Property Name="Authors" Type="Edm.String" Nullable="true"/>
                        <Property Name="Description" Type="Edm.String" Nullable="true"/>
                        <Property Name="PackageHash" Type="Edm.String" Nullable="true"/>
                        <Property Name="PackageHashAlgorithm" Type="Edm.String" Nullable="true"/>
                        <Property Name="PackageSize" Type="Edm.Int64" Nullable="false"/>
                        <Property Name="Published" Type="Edm.DateTime" Nullable="false"/>
                        <Property Name="IsLatestVersion" Type="Edm.Boolean" Nullable="false"/>
                        <Property Name="IsAbsoluteLatestVersion" Type="Edm.Boolean" Nullable="false"/>
                        <Property Name="Listed" Type="Edm.Boolean" Nullable="false"/>
                        <Property Name="Dependencies" Type="Edm.String" Nullable="true"/>
                      </EntityType>
                      <EntityContainer Name="V2FeedContext" m:IsDefaultEntityContainer="true">
                        <EntitySet Name="Packages" EntityType="NuGetGallery.V2FeedPackage"/>
                        <FunctionImport Name="Search" ReturnType="Collection(NuGetGallery.V2FeedPackage)" EntitySet="Packages" m:HttpMethod="GET">
                          <Parameter Name="searchTerm" Type="Edm.String" Mode="In"/>
                          <Parameter Name="targetFramework" Type="Edm.String" Mode="In"/>
                          <Parameter Name="includePrerelease" Type="Edm.Boolean" Mode="In"/>
                        </FunctionImport>
                        <FunctionImport Name="FindPackagesById" ReturnType="Collection(NuGetGallery.V2FeedPackage)" EntitySet="Packages" m:HttpMethod="GET">
                          <Parameter Name="id" Type="Edm.String" Mode="In"/>
                        </FunctionImport>
                      </EntityContainer>
                    </Schema>
                  </edmx:DataServices>
                </edmx:Edmx>
                """;
        return xmlResponse(edmx, METADATA_CONTENT_TYPE);
    }

    /** {@code GET {feed}/FindPackagesById()?id='X'} — all versions of a package as an Atom feed. */
    public FormatResponse findPackagesById(RepositoryConfig repo, String id, String baseUrl) {
        String idLower = NugetNames.lowerId(id);
        List<ComponentEntity> components = sortedComponents(repo, idLower);
        String repoBase = baseUrl + "/repository/" + repo.name();
        return feed(repo, "FindPackagesById", repoBase, components);
    }

    /** {@code GET {feed}/Packages(Id='X',Version='Y')} — a single package version as an Atom entry. */
    public FormatResponse packageEntry(RepositoryConfig repo, String id, String version, String baseUrl) {
        String idLower = NugetNames.lowerId(id);
        String versionLower = NugetNames.lowerVersion(version);
        String repoBase = baseUrl + "/repository/" + repo.name();
        List<ComponentEntity> components = sortedComponents(repo, idLower);
        for (ComponentEntity component : components) {
            if (component.getVersion().toLowerCase(Locale.ROOT).equals(versionLower)) {
                boolean isLatest = component.equals(components.get(components.size() - 1));
                String entry = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                        + entryXml(repo, component, repoBase, isLatest, true);
                return xmlResponse(entry, ENTRY_CONTENT_TYPE);
            }
        }
        return new NotFoundResponse("Package version not found: " + idLower + " " + versionLower);
    }

    /** {@code GET {feed}/Search()?searchTerm='X'} — search as an Atom feed (latest version per id). */
    public FormatResponse search(RepositoryConfig repo, String searchTerm, String baseUrl) {
        List<ComponentEntity> components = (searchTerm == null || searchTerm.isBlank())
                ? componentRepository.findByRepositoryId(repo.id(), Pageable.unpaged()).getContent()
                : componentRepository
                        .findByRepositoryIdAndFilter(repo.id(), searchTerm.trim(), Pageable.unpaged())
                        .getContent();

        // Keep only the latest version per package id, ordered by id.
        Map<String, ComponentEntity> latestById = new java.util.TreeMap<>();
        for (ComponentEntity c : components) {
            latestById.merge(c.getName(), c, (a, b) ->
                    NugetNames.versionOrder().compare(a.getVersion(), b.getVersion()) >= 0 ? a : b);
        }

        String repoBase = baseUrl + "/repository/" + repo.name();
        return feed(repo, "Search", repoBase, List.copyOf(latestById.values()));
    }

    // ── Internals ───────────────────────────────────────────────────────

    private List<ComponentEntity> sortedComponents(RepositoryConfig repo, String idLower) {
        return componentRepository.findByRepositoryIdAndNamespaceAndName(repo.id(), null, idLower).stream()
                .sorted(Comparator.comparing(ComponentEntity::getVersion, NugetNames.versionOrder()))
                .toList();
    }

    private FormatResponse feed(
            RepositoryConfig repo, String feedTitle, String repoBase, List<ComponentEntity> components) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
        sb.append("<feed xml:base=\"").append(escapeAttr(repoBase)).append("/\" ")
                .append("xmlns=\"http://www.w3.org/2005/Atom\" ")
                .append("xmlns:d=\"http://schemas.microsoft.com/ado/2007/08/dataservices\" ")
                .append("xmlns:m=\"http://schemas.microsoft.com/ado/2007/08/dataservices/metadata\">\n");
        sb.append("  <title type=\"text\">").append(escapeText(feedTitle)).append("</title>\n");
        sb.append("  <id>").append(escapeText(repoBase + "/" + feedTitle)).append("</id>\n");
        for (int i = 0; i < components.size(); i++) {
            boolean isLatest = i == components.size() - 1;
            sb.append(entryXml(repo, components.get(i), repoBase, isLatest, false));
        }
        sb.append("</feed>\n");
        return xmlResponse(sb.toString(), ATOM_CONTENT_TYPE);
    }

    private String entryXml(
            RepositoryConfig repo,
            ComponentEntity component,
            String repoBase,
            boolean isLatest,
            boolean standalone) {
        String idLower = component.getName();
        String version = component.getVersion();
        String versionLower = version.toLowerCase(Locale.ROOT);
        Map<String, Object> attrs = component.getAttributes();
        String originalId = attrs.get("originalId") instanceof String s ? s : idLower;

        String contentUrl = repoBase + "/v3-flatcontainer/" + idLower + "/" + versionLower
                + "/" + idLower + "." + versionLower + ".nupkg";
        String entryId = repoBase + "/Packages(Id='" + originalId + "',Version='" + version + "')";

        Optional<AssetEntity> nupkg = assetRepository.findByRepositoryIdAndPath(
                repo.id(),
                "v3-flatcontainer/" + idLower + "/" + versionLower + "/" + idLower + "." + versionLower + ".nupkg");
        long size = nupkg.map(a -> a.getSize() != null ? a.getSize() : 0L).orElse(0L);
        String hash = nupkg.map(AssetEntity::getChecksumSha512).orElse(null);

        String description = attrs.get("description") instanceof String d ? d : "";
        String authors = attrs.get("authors") instanceof String a ? a : "";
        String published = component.getCreatedAt() != null
                ? component.getCreatedAt().toString().replace("Z", "")
                : "1900-01-01T00:00:00";

        StringBuilder e = new StringBuilder();
        String indent = standalone ? "" : "  ";
        e.append(indent).append("<entry");
        if (standalone) {
            e.append(" xml:base=\"").append(escapeAttr(repoBase)).append("/\"")
                    .append(" xmlns=\"http://www.w3.org/2005/Atom\"")
                    .append(" xmlns:d=\"http://schemas.microsoft.com/ado/2007/08/dataservices\"")
                    .append(" xmlns:m=\"http://schemas.microsoft.com/ado/2007/08/dataservices/metadata\"");
        }
        e.append(">\n");
        e.append(indent).append("  <id>").append(escapeText(entryId)).append("</id>\n");
        e.append(indent).append("  <title type=\"text\">").append(escapeText(originalId)).append("</title>\n");
        e.append(indent).append("  <updated>").append(escapeText(published)).append("Z</updated>\n");
        e.append(indent).append("  <author><name>").append(escapeText(authors)).append("</name></author>\n");
        e.append(indent).append("  <content type=\"application/zip\" src=\"")
                .append(escapeAttr(contentUrl)).append("\"/>\n");
        e.append(indent).append("  <m:properties>\n");
        e.append(prop(indent, "Id", originalId, null));
        e.append(prop(indent, "Version", version, null));
        e.append(prop(indent, "NormalizedVersion", versionLower, null));
        e.append(prop(indent, "Authors", authors, null));
        e.append(prop(indent, "Description", description, null));
        e.append(prop(indent, "PackageHash", hash, null));
        e.append(prop(indent, "PackageHashAlgorithm", hash != null ? "SHA512" : null, null));
        e.append(prop(indent, "PackageSize", Long.toString(size), "Edm.Int64"));
        e.append(prop(indent, "Published", published, "Edm.DateTime"));
        e.append(prop(indent, "IsLatestVersion", Boolean.toString(isLatest), "Edm.Boolean"));
        e.append(prop(indent, "IsAbsoluteLatestVersion", Boolean.toString(isLatest), "Edm.Boolean"));
        e.append(prop(indent, "Listed", "true", "Edm.Boolean"));
        e.append(indent).append("  </m:properties>\n");
        e.append(indent).append("</entry>\n");
        return e.toString();
    }

    private String prop(String indent, String name, String value, String edmType) {
        String typeAttr = edmType != null ? " m:type=\"" + edmType + "\"" : "";
        if (value == null) {
            return indent + "    <d:" + name + " m:null=\"true\"" + typeAttr + "/>\n";
        }
        return indent + "    <d:" + name + typeAttr + ">" + escapeText(value) + "</d:" + name + ">\n";
    }

    private FormatResponse xmlResponse(String xml, String contentType) {
        byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);
        return new ContentResponse(
                new ByteArrayInputStream(bytes), contentType, bytes.length, Map.of(), Map.of());
    }

    private static String escapeText(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String escapeAttr(String s) {
        return escapeText(s).replace("\"", "&quot;");
    }
}
