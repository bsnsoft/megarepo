package de.bsnsoft.megarepo.format.maven.metadata;

import de.bsnsoft.megarepo.core.storage.BlobRef;
import de.bsnsoft.megarepo.core.storage.BlobStore;
import de.bsnsoft.megarepo.database.entity.AssetEntity;
import de.bsnsoft.megarepo.database.entity.ComponentEntity;
import de.bsnsoft.megarepo.database.repository.AssetJpaRepository;
import de.bsnsoft.megarepo.database.repository.ComponentJpaRepository;
import de.bsnsoft.megarepo.storage.BlobStoreManager;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class MavenMetadataGenerator {

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

    private final ComponentJpaRepository componentJpaRepository;
    private final AssetJpaRepository assetJpaRepository;
    private final BlobStoreManager blobStoreManager;

    public MavenMetadataGenerator(
            ComponentJpaRepository componentJpaRepository,
            AssetJpaRepository assetJpaRepository,
            BlobStoreManager blobStoreManager) {
        this.componentJpaRepository = componentJpaRepository;
        this.assetJpaRepository = assetJpaRepository;
        this.blobStoreManager = blobStoreManager;
    }

    /**
     * Generates artifact-level (A-level) maven-metadata.xml listing all versions
     * of a given groupId:artifactId in the repository.
     */
    public String generateMetadata(UUID repoId, String blobStoreName, String groupId, String artifactId) {
        List<ComponentEntity> components =
                componentJpaRepository.findByRepositoryIdAndNamespaceAndName(repoId, groupId, artifactId);

        List<String> versions = components.stream()
                .map(ComponentEntity::getVersion)
                .distinct()
                .sorted(MavenVersionComparator.INSTANCE)
                .toList();

        String latest = versions.isEmpty() ? null : versions.getLast();
        String release = versions.stream()
                .filter(v -> !v.endsWith("-SNAPSHOT"))
                .reduce((first, second) -> second)
                .orElse(null);

        String lastUpdated = TIMESTAMP_FORMAT.format(Instant.now());

        var model = new MavenMetadataModel(
                groupId,
                artifactId,
                null,
                new MavenMetadataModel.Versioning(latest, release, versions, lastUpdated, null, null));

        String xml = serializeToXml(model);

        storeMetadataAsset(repoId, blobStoreName, groupId, artifactId, null, xml);

        return xml;
    }

    /**
     * Generates version-level (V-level) maven-metadata.xml for a SNAPSHOT version.
     */
    public String generateSnapshotMetadata(
            UUID repoId, String blobStoreName, String groupId, String artifactId, String version) {
        if (!version.endsWith("-SNAPSHOT")) {
            throw new IllegalArgumentException("Version must end with -SNAPSHOT: " + version);
        }

        var componentOpt = componentJpaRepository.findByRepositoryIdAndNamespaceAndNameAndVersion(
                repoId, groupId, artifactId, version);

        String lastUpdated = TIMESTAMP_FORMAT.format(Instant.now());

        List<MavenMetadataModel.SnapshotVersion> snapshotVersions = new ArrayList<>();
        String snapshotTimestamp = null;
        int buildNumber = 1;

        if (componentOpt.isPresent()) {
            var component = componentOpt.get();
            var assets = assetJpaRepository.findByComponentId(component.getId(), Pageable.unpaged());

            // Derive build number from the number of assets (simplified heuristic)
            buildNumber = Math.max(1, (int) assets.getTotalElements());

            snapshotTimestamp = DateTimeFormatter.ofPattern("yyyyMMdd.HHmmss")
                    .withZone(ZoneOffset.UTC)
                    .format(Instant.now());

            String baseVersion = version.replace("-SNAPSHOT", "");

            for (var asset : assets) {
                String path = asset.getPath();
                String fileName = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;

                String extension = extractExtension(fileName);
                String classifier = extractClassifier(fileName, artifactId, baseVersion);

                snapshotVersions.add(new MavenMetadataModel.SnapshotVersion(
                        classifier, extension, baseVersion + "-" + snapshotTimestamp + "-" + buildNumber, lastUpdated));
            }
        }

        var snapshot = new MavenMetadataModel.SnapshotInfo(
                snapshotTimestamp != null ? snapshotTimestamp : TIMESTAMP_FORMAT.format(Instant.now()), buildNumber);

        var model = new MavenMetadataModel(
                groupId,
                artifactId,
                version,
                new MavenMetadataModel.Versioning(null, null, null, lastUpdated, snapshot, snapshotVersions));

        String xml = serializeToXml(model);

        storeMetadataAsset(repoId, blobStoreName, groupId, artifactId, version, xml);

        return xml;
    }

    public String serializeToXml(MavenMetadataModel model) {
        var sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<metadata>\n");
        appendElement(sb, "  ", "groupId", model.groupId());
        appendElement(sb, "  ", "artifactId", model.artifactId());

        if (model.version() != null) {
            appendElement(sb, "  ", "version", model.version());
        }

        if (model.versioning() != null) {
            sb.append("  <versioning>\n");
            var v = model.versioning();

            if (v.latest() != null) {
                appendElement(sb, "    ", "latest", v.latest());
            }
            if (v.release() != null) {
                appendElement(sb, "    ", "release", v.release());
            }

            if (v.versions() != null && !v.versions().isEmpty()) {
                sb.append("    <versions>\n");
                for (String ver : v.versions()) {
                    appendElement(sb, "      ", "version", ver);
                }
                sb.append("    </versions>\n");
            }

            if (v.snapshot() != null) {
                sb.append("    <snapshot>\n");
                appendElement(sb, "      ", "timestamp", v.snapshot().timestamp());
                appendElement(sb, "      ", "buildNumber", String.valueOf(v.snapshot().buildNumber()));
                sb.append("    </snapshot>\n");
            }

            if (v.snapshotVersions() != null && !v.snapshotVersions().isEmpty()) {
                sb.append("    <snapshotVersions>\n");
                for (var sv : v.snapshotVersions()) {
                    sb.append("      <snapshotVersion>\n");
                    if (sv.classifier() != null && !sv.classifier().isEmpty()) {
                        appendElement(sb, "        ", "classifier", sv.classifier());
                    }
                    appendElement(sb, "        ", "extension", sv.extension());
                    appendElement(sb, "        ", "value", sv.value());
                    appendElement(sb, "        ", "updated", sv.updated());
                    sb.append("      </snapshotVersion>\n");
                }
                sb.append("    </snapshotVersions>\n");
            }

            appendElement(sb, "    ", "lastUpdated", v.lastUpdated());
            sb.append("  </versioning>\n");
        }

        sb.append("</metadata>\n");
        return sb.toString();
    }

    public MavenMetadataModel parseFromXml(String xml) {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            var builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new StringReader(xml)));
            doc.getDocumentElement().normalize();

            Element root = doc.getDocumentElement();

            String groupId = getChildText(root, "groupId");
            String artifactId = getChildText(root, "artifactId");
            String version = getChildText(root, "version");

            MavenMetadataModel.Versioning versioning = null;
            NodeList versioningNodes = root.getElementsByTagName("versioning");
            if (versioningNodes.getLength() > 0) {
                Element versioningEl = (Element) versioningNodes.item(0);
                versioning = parseVersioning(versioningEl);
            }

            return new MavenMetadataModel(groupId, artifactId, version, versioning);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse maven-metadata.xml", e);
        }
    }

    private MavenMetadataModel.Versioning parseVersioning(Element el) {
        String latest = getChildText(el, "latest");
        String release = getChildText(el, "release");
        String lastUpdated = getChildText(el, "lastUpdated");

        List<String> versions = new ArrayList<>();
        NodeList versionsNodes = el.getElementsByTagName("versions");
        if (versionsNodes.getLength() > 0) {
            Element versionsEl = (Element) versionsNodes.item(0);
            NodeList versionNodes = versionsEl.getElementsByTagName("version");
            for (int i = 0; i < versionNodes.getLength(); i++) {
                versions.add(versionNodes.item(i).getTextContent().trim());
            }
        }

        MavenMetadataModel.SnapshotInfo snapshot = null;
        NodeList snapshotNodes = el.getElementsByTagName("snapshot");
        if (snapshotNodes.getLength() > 0) {
            Element snapshotEl = (Element) snapshotNodes.item(0);
            String timestamp = getChildText(snapshotEl, "timestamp");
            String buildNumberStr = getChildText(snapshotEl, "buildNumber");
            int buildNumber = buildNumberStr != null ? Integer.parseInt(buildNumberStr) : 0;
            snapshot = new MavenMetadataModel.SnapshotInfo(timestamp, buildNumber);
        }

        List<MavenMetadataModel.SnapshotVersion> snapshotVersions = new ArrayList<>();
        NodeList svNodes = el.getElementsByTagName("snapshotVersion");
        for (int i = 0; i < svNodes.getLength(); i++) {
            Element svEl = (Element) svNodes.item(i);
            snapshotVersions.add(new MavenMetadataModel.SnapshotVersion(
                    getChildText(svEl, "classifier"),
                    getChildText(svEl, "extension"),
                    getChildText(svEl, "value"),
                    getChildText(svEl, "updated")));
        }

        return new MavenMetadataModel.Versioning(
                latest,
                release,
                versions.isEmpty() ? null : versions,
                lastUpdated,
                snapshot,
                snapshotVersions.isEmpty() ? null : snapshotVersions);
    }

    private String getChildText(Element parent, String tagName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element element && tagName.equals(element.getTagName())) {
                String text = element.getTextContent();
                return text != null && !text.isBlank() ? text.trim() : null;
            }
        }
        return null;
    }

    private void appendElement(StringBuilder sb, String indent, String tag, String value) {
        sb.append(indent).append('<').append(tag).append('>');
        sb.append(escapeXml(value));
        sb.append("</").append(tag).append(">\n");
    }

    private String escapeXml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private void storeMetadataAsset(
            UUID repoId, String blobStoreName, String groupId, String artifactId, String version, String xml) {
        String groupPath = groupId.replace('.', '/');
        String path = version != null
                ? groupPath + "/" + artifactId + "/" + version + "/maven-metadata.xml"
                : groupPath + "/" + artifactId + "/maven-metadata.xml";

        byte[] xmlBytes = xml.getBytes(StandardCharsets.UTF_8);

        BlobStore blobStore = blobStoreManager.get(blobStoreName);
        BlobRef blobRef = blobStore.store(
                new ByteArrayInputStream(xmlBytes), xmlBytes.length, Map.of("Content-Type", "application/xml"));

        Instant now = Instant.now();

        AssetEntity asset = assetJpaRepository.findByRepositoryIdAndPath(repoId, path).orElseGet(() -> {
            AssetEntity newAsset = new AssetEntity();
            newAsset.setRepositoryId(repoId);
            newAsset.setPath(path);
            newAsset.setFormat("maven2");
            newAsset.setCreatedAt(now);
            return newAsset;
        });

        asset.setBlobRef(blobRef.toExternalForm());
        asset.setContentType("application/xml");
        asset.setSize((long) xmlBytes.length);
        asset.setGenerated(true);
        asset.setLastModified(now);
        asset.setUpdatedAt(now);

        // Compute checksums
        try {
            asset.setChecksumMd5(computeChecksum(xmlBytes, "MD5"));
            asset.setChecksumSha1(computeChecksum(xmlBytes, "SHA-1"));
            asset.setChecksumSha256(computeChecksum(xmlBytes, "SHA-256"));
        } catch (Exception e) {
            // checksums are best-effort for generated metadata
        }

        assetJpaRepository.save(asset);
    }

    private String computeChecksum(byte[] data, String algorithm) throws Exception {
        MessageDigest digest = MessageDigest.getInstance(algorithm);
        byte[] hash = digest.digest(data);
        return HexFormat.of().formatHex(hash);
    }

    private String extractExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex >= 0) {
            return fileName.substring(dotIndex + 1);
        }
        return "";
    }

    private String extractClassifier(String fileName, String artifactId, String baseVersion) {
        // Pattern: artifactId-version[-classifier].extension
        String prefix = artifactId + "-" + baseVersion;
        if (fileName.startsWith(prefix)) {
            String remainder = fileName.substring(prefix.length());
            if (remainder.startsWith("-")) {
                int dotIndex = remainder.indexOf('.');
                if (dotIndex > 1) {
                    return remainder.substring(1, dotIndex);
                }
            }
        }
        return null;
    }

    /**
     * Simple Maven version comparator that handles numeric comparison for version segments.
     */
    private enum MavenVersionComparator implements Comparator<String> {
        INSTANCE;

        @Override
        public int compare(String v1, String v2) {
            String[] parts1 = v1.split("[.\\-]");
            String[] parts2 = v2.split("[.\\-]");
            int len = Math.max(parts1.length, parts2.length);

            for (int i = 0; i < len; i++) {
                String p1 = i < parts1.length ? parts1[i] : "0";
                String p2 = i < parts2.length ? parts2[i] : "0";

                // Try numeric comparison first
                try {
                    int n1 = Integer.parseInt(p1);
                    int n2 = Integer.parseInt(p2);
                    int cmp = Integer.compare(n1, n2);
                    if (cmp != 0) {
                        return cmp;
                    }
                } catch (NumberFormatException e) {
                    int cmp = p1.compareTo(p2);
                    if (cmp != 0) {
                        return cmp;
                    }
                }
            }
            return 0;
        }
    }
}
