package de.bsnsoft.megarepo.format.maven.firewall;

import com.github.packageurl.PackageURL;
import de.bsnsoft.megarepo.core.storage.Blob;
import de.bsnsoft.megarepo.database.entity.AssetEntity;
import de.bsnsoft.megarepo.database.entity.RepositoryEntity;
import de.bsnsoft.megarepo.database.repository.RepositoryJpaRepository;
import de.bsnsoft.megarepo.repository.AssetService;
import de.bsnsoft.megarepo.repository.firewall.facts.ComponentFactsHttpClient;
import de.bsnsoft.megarepo.repository.firewall.facts.ComponentFactsProperties;
import de.bsnsoft.megarepo.repository.firewall.facts.ComponentFactsSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Publication date and declared licenses for {@code pkg:maven}.
 *
 * <h2>Where the two facts come from</h2>
 *
 * The licenses come out of the POM's {@code <licenses>} block, which is Maven's
 * declaration and the only one this design allows to be read. The publication
 * date does not exist in a POM at all — no Maven descriptor records when it was
 * released — so it is taken from the {@code Last-Modified} of the POM in the
 * remote repository, which for an immutable repository such as Central is
 * exactly the moment the version appeared.
 *
 * <h2>The stored POM, when there is one</h2>
 *
 * With {@code megarepo.firewall.facts.prefer-local-metadata} on, the POM this
 * instance already stores is read first for the licenses. It costs no outbound
 * request and it describes the artifact actually being served rather than what
 * the remote says today. It cannot supply the date, so the remote is still asked
 * for that — unless the remote does not have the artifact at all, which is the
 * ordinary case for a package published into a hosted repository and the one
 * case where the stored POM is the only truth there is.
 *
 * <p>Parent-POM inheritance is deliberately not followed: a resolution that
 * walked a parent chain would be several more outbound requests per component
 * for a field most parents do not set, and "the POM of this artifact declares
 * no license" is a fact a license policy is entitled to act on.
 */
@Component
public class MavenComponentFactsSource implements ComponentFactsSource {

    private static final Logger log = LoggerFactory.getLogger(MavenComponentFactsSource.class);

    /** Short id written to {@code firewall_component_facts.source}. */
    static final String SOURCE_ID = "maven-pom";

    static final String DEFAULT_BASE_URL = "https://repo1.maven.org/maven2/";

    /** Repository formats whose components are Maven artifacts. Mirrors {@code MavenPurlMapper}. */
    private static final List<String> MAVEN_FORMATS = List.of("maven2", "maven");

    /** A POM past this is not a descriptor. */
    private static final int MAX_POM_BYTES = 4 * 1024 * 1024;

    private final ComponentFactsHttpClient http;
    private final ComponentFactsProperties properties;
    private final RepositoryJpaRepository repositories;
    private final AssetService assets;
    private final String baseUrl;

    public MavenComponentFactsSource(
            ComponentFactsHttpClient http,
            ComponentFactsProperties properties,
            RepositoryJpaRepository repositories,
            AssetService assets,
            @Value("${megarepo.firewall.facts.maven.base-url:" + DEFAULT_BASE_URL + "}") String baseUrl) {
        this.http = http;
        this.properties = properties;
        this.repositories = repositories;
        this.assets = assets;
        this.baseUrl = withTrailingSlash(baseUrl);
    }

    @Override
    public String purlType() {
        return PackageURL.StandardTypes.MAVEN;
    }

    @Override
    public Optional<ResolvedFacts> resolve(PackageURL purl) throws ComponentFactsException {
        String groupId = purl.getNamespace();
        String artifactId = purl.getName();
        String version = purl.getVersion();
        if (isUnusable(groupId) || isUnusable(artifactId) || isUnusable(version)) {
            // pkg:maven without a namespace is not a Maven coordinate; nothing can
            // be looked up and nothing will change if we retry.
            return Optional.empty();
        }

        String path = groupId.replace('.', '/')
                + "/" + artifactId
                + "/" + version
                + "/" + artifactId + "-" + version + ".pom";

        List<String> localLicenses = properties.preferLocalMetadata() ? storedPomLicenses(path) : null;

        ComponentFactsHttpClient.Response response =
                http.get(baseUrl + path, Map.of("Accept", "application/xml"));

        if (response.isNotFound()) {
            if (localLicenses != null) {
                // Hosted-only artifact: the remote never had it, so the stored POM
                // is the whole answer. No date, which MIN_AGE reads as "cannot
                // judge" rather than "brand new".
                return Optional.of(new ResolvedFacts(
                        null, localLicenses, ResolvedFacts.PACKAGE_METADATA, SOURCE_ID));
            }
            return Optional.empty();
        }
        if (!response.isSuccess()) {
            throw new ComponentFactsException(
                    "Maven repository answered HTTP %d for %s".formatted(response.statusCode(), path));
        }

        List<String> licenses = localLicenses != null ? localLicenses : parseLicenses(response.body());
        String licenseSource = localLicenses != null
                ? ResolvedFacts.PACKAGE_METADATA
                : ResolvedFacts.UPSTREAM_REGISTRY;
        return Optional.of(new ResolvedFacts(
                response.lastModified().orElse(null), licenses, licenseSource, SOURCE_ID));
    }

    /**
     * The licenses declared by the POM this instance already stores, if it stores
     * one.
     *
     * <p>Facts are keyed on the purl and not on a repository, so the first stored
     * copy answers for all of them. Repositories are visited in name order so
     * that two instances with the same content give the same answer — an
     * arbitrary iteration order would make a license verdict depend on row
     * ordering, which is not something anybody can argue with.
     *
     * @return the declared licenses (possibly empty — "this POM declares none" is
     *     a fact), or {@code null} when no POM is stored here
     */
    private List<String> storedPomLicenses(String path) {
        if (repositories == null || assets == null) {
            return null;
        }
        try {
            List<RepositoryEntity> candidates = new ArrayList<>();
            for (String format : MAVEN_FORMATS) {
                candidates.addAll(repositories.findByFormat(format));
            }
            candidates.sort(Comparator.comparing(RepositoryEntity::getName,
                    Comparator.nullsLast(Comparator.naturalOrder())));

            for (RepositoryEntity repository : candidates) {
                Optional<AssetEntity> asset = assets.getAsset(repository.getId(), path);
                if (asset.isEmpty()) {
                    continue;
                }
                Optional<byte[]> content = read(asset.get());
                if (content.isEmpty()) {
                    continue;
                }
                List<String> licenses =
                        parseLicenses(new String(content.get(), StandardCharsets.UTF_8));
                log.debug("Read declared licenses for {} from the stored POM in {}: {}",
                        path, repository.getName(), licenses);
                return licenses;
            }
        } catch (RuntimeException e) {
            // The remote answer is still available; a local read that fails is a
            // missed optimisation, not a failed resolution.
            log.debug("Could not read a stored POM for {}: {}", path, e.toString());
        }
        return null;
    }

    private Optional<byte[]> read(AssetEntity asset) {
        try (Blob blob = assets.getAssetContent(asset).orElse(null)) {
            if (blob == null) {
                return Optional.empty();
            }
            InputStream stream = blob.inputStream();
            byte[] bytes = stream.readNBytes(MAX_POM_BYTES + 1);
            return bytes.length > MAX_POM_BYTES ? Optional.empty() : Optional.of(bytes);
        } catch (Exception e) {
            log.debug("Could not read the stored POM blob: {}", e.toString());
            return Optional.empty();
        }
    }

    /**
     * {@code <project><licenses><license><name>} — the declaration, nothing else.
     *
     * <p>{@code <url>} is the fallback when a license element carries no name,
     * because "GPL, see this link" is still a declaration and dropping it would
     * make the component look unlicensed to a deny-by-default policy.
     */
    static List<String> parseLicenses(String pomXml) {
        List<String> licenses = new ArrayList<>();
        if (pomXml == null || pomXml.isBlank()) {
            return licenses;
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            Document document = factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(pomXml.getBytes(StandardCharsets.UTF_8)));
            Element root = document.getDocumentElement();
            if (root == null || !"project".equals(root.getTagName())) {
                return licenses;
            }
            Element licensesElement = directChild(root, "licenses");
            if (licensesElement == null) {
                return licenses;
            }
            NodeList children = licensesElement.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                if (children.item(i) instanceof Element license && "license".equals(license.getTagName())) {
                    String name = text(directChild(license, "name"));
                    String url = text(directChild(license, "url"));
                    String declared = name != null ? name : url;
                    if (declared != null && !licenses.contains(declared)) {
                        licenses.add(declared);
                    }
                }
            }
        } catch (Exception e) {
            // A POM that will not parse declares nothing readable. Treating that as
            // "no license" rather than as a retryable failure is deliberate: the
            // bytes will not improve on the next attempt.
            log.debug("Could not parse a POM for its licenses: {}", e.toString());
        }
        return licenses;
    }

    private static Element directChild(Element parent, String tagName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element element && tagName.equals(element.getTagName())) {
                return element;
            }
        }
        return null;
    }

    private static String text(Element element) {
        if (element == null) {
            return null;
        }
        String value = element.getTextContent();
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static boolean isUnusable(String segment) {
        return segment == null
                || segment.isBlank()
                || segment.contains("/")
                || segment.contains("\\")
                || segment.contains("..");
    }

    private static String withTrailingSlash(String value) {
        String trimmed = value == null || value.isBlank() ? DEFAULT_BASE_URL : value.trim();
        return trimmed.endsWith("/") ? trimmed : trimmed + "/";
    }
}
