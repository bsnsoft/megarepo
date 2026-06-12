package de.bsnsoft.megarepo.format.npm.publish;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.bsnsoft.megarepo.core.format.FormatResponse;
import de.bsnsoft.megarepo.core.format.FormatResponse.CreatedResponse;
import de.bsnsoft.megarepo.core.format.FormatResponse.ErrorResponse;
import de.bsnsoft.megarepo.core.repository.RepositoryConfig;
import de.bsnsoft.megarepo.core.storage.Blob;
import de.bsnsoft.megarepo.core.storage.BlobRef;
import de.bsnsoft.megarepo.core.storage.BlobStore;
import de.bsnsoft.megarepo.database.entity.AssetEntity;
import de.bsnsoft.megarepo.database.entity.ComponentEntity;
import de.bsnsoft.megarepo.database.repository.AssetJpaRepository;
import de.bsnsoft.megarepo.database.repository.ComponentJpaRepository;
import de.bsnsoft.megarepo.format.npm.scope.ScopedPackageResolver;
import de.bsnsoft.megarepo.storage.BlobStoreManager;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;

@Component
public class NpmPublishHandler {

    private static final Logger log = LoggerFactory.getLogger(NpmPublishHandler.class);
    private static final String FORMAT = "npm";

    private final BlobStoreManager blobStoreManager;
    private final ComponentJpaRepository componentRepository;
    private final AssetJpaRepository assetRepository;
    private final ScopedPackageResolver scopeResolver;
    private final ObjectMapper objectMapper;

    public NpmPublishHandler(
            BlobStoreManager blobStoreManager,
            ComponentJpaRepository componentRepository,
            AssetJpaRepository assetRepository,
            ScopedPackageResolver scopeResolver) {
        this.blobStoreManager = blobStoreManager;
        this.componentRepository = componentRepository;
        this.assetRepository = assetRepository;
        this.scopeResolver = scopeResolver;
        this.objectMapper = new ObjectMapper();
    }

    public FormatResponse handlePublish(
            RepositoryConfig repo, String packageName, HttpServletRequest request) {
        try {
            byte[] bodyBytes = request.getInputStream().readAllBytes();
            JsonNode root = objectMapper.readTree(bodyBytes);

            // Extract version info from versions object
            JsonNode versionsNode = root.get("versions");
            if (versionsNode == null || !versionsNode.isObject() || versionsNode.isEmpty()) {
                return new ErrorResponse(400, "Missing or empty 'versions' in publish payload");
            }

            // Extract attachments
            JsonNode attachmentsNode = root.get("_attachments");
            if (attachmentsNode == null || !attachmentsNode.isObject() || attachmentsNode.isEmpty()) {
                return new ErrorResponse(400, "Missing or empty '_attachments' in publish payload");
            }

            // Process each version
            Iterator<Map.Entry<String, JsonNode>> versionEntries = versionsNode.fields();
            while (versionEntries.hasNext()) {
                Map.Entry<String, JsonNode> versionEntry = versionEntries.next();
                String version = versionEntry.getKey();
                JsonNode versionMetadata = versionEntry.getValue();

                FormatResponse result = publishVersion(
                        repo, packageName, version, versionMetadata, attachmentsNode, request);
                if (result instanceof ErrorResponse) {
                    return result;
                }
            }

            return new CreatedResponse(packageName, Map.of());

        } catch (IOException e) {
            log.error("Failed to read npm publish body for package={}: {}", packageName, e.getMessage());
            return new ErrorResponse(400, "Failed to read publish payload: " + e.getMessage());
        } catch (Exception e) {
            log.error("Failed to process npm publish for package={}: {}", packageName, e.getMessage());
            return new ErrorResponse(400, "Invalid publish payload: " + e.getMessage());
        }
    }

    private FormatResponse publishVersion(
            RepositoryConfig repo,
            String packageName,
            String version,
            JsonNode versionMetadata,
            JsonNode attachmentsNode,
            HttpServletRequest request) {

        String namespace = null;
        String name = packageName;

        if (scopeResolver.isScoped(packageName)) {
            namespace = scopeResolver.getScope(packageName);
            name = scopeResolver.getPackageName(packageName);
        }

        // Build the expected tarball filename.
        // npm uses the convention: {scope-without-@}-{name}-{version}.tgz for scoped packages
        // e.g. @megarepo/test-pkg@1.0.0 -> megarepo-test-pkg-1.0.0.tgz
        // Also check for just {name}-{version}.tgz as a fallback.
        String tarballFilename = name + "-" + version + ".tgz";
        String scopedTarballFilename = null;
        if (namespace != null) {
            String scopeWithoutAt = namespace.startsWith("@") ? namespace.substring(1) : namespace;
            scopedTarballFilename = scopeWithoutAt + "-" + name + "-" + version + ".tgz";
        }

        // Find the attachment matching this version
        JsonNode attachment = attachmentsNode.get(tarballFilename);
        if (attachment == null && scopedTarballFilename != null) {
            attachment = attachmentsNode.get(scopedTarballFilename);
            if (attachment != null) {
                tarballFilename = scopedTarballFilename;
            }
        }
        if (attachment == null) {
            // Last resort: try the first attachment
            Iterator<String> fieldNames = attachmentsNode.fieldNames();
            if (fieldNames.hasNext()) {
                tarballFilename = fieldNames.next();
                attachment = attachmentsNode.get(tarballFilename);
            }
        }
        if (attachment == null) {
            return new ErrorResponse(400, "No attachment found for tarball: " + tarballFilename);
        }

        JsonNode dataNode = attachment.get("data");
        if (dataNode == null || !dataNode.isTextual()) {
            return new ErrorResponse(400, "Attachment missing 'data' field for: " + tarballFilename);
        }

        // Decode base64 tarball
        byte[] tarballData;
        try {
            tarballData = Base64.getDecoder().decode(dataNode.asText());
        } catch (IllegalArgumentException e) {
            return new ErrorResponse(400, "Invalid base64 data for attachment: " + tarballFilename);
        }

        return publishTarball(
                repo, packageName, version, versionMetadata, tarballData,
                request.getRemoteUser(), request.getRemoteAddr());
    }

    /**
     * Stores a published npm package version from raw tarball bytes — the
     * shared core of both `npm publish` (base64 attachment) and manual
     * uploads (Web-UI / REST). Registry metadata needs no regeneration: it is
     * built dynamically from components/assets on every metadata GET.
     */
    public FormatResponse publishTarball(
            RepositoryConfig repo,
            String packageName,
            String version,
            JsonNode versionMetadata,
            byte[] tarballData,
            String username,
            String clientIp) {

        String namespace = null;
        String name = packageName;

        if (scopeResolver.isScoped(packageName)) {
            namespace = scopeResolver.getScope(packageName);
            name = scopeResolver.getPackageName(packageName);
        }

        // Store the tarball blob
        BlobStore blobStore = blobStoreManager.get(repo.blobStoreName());
        Map<String, String> headers = Map.of("Content-Type", "application/gzip");
        BlobRef blobRef = blobStore.store(new ByteArrayInputStream(tarballData), tarballData.length, headers);

        Optional<Blob> storedBlob = blobStore.get(blobRef);
        if (storedBlob.isEmpty()) {
            return new ErrorResponse(500, "Failed to store tarball blob");
        }

        // Create or update component
        ComponentEntity component = findOrCreateComponent(repo, namespace, name, version);

        // Store version metadata as component attributes
        Map<String, Object> attributes = new HashMap<>(component.getAttributes());
        if (versionMetadata.has("description")) {
            attributes.put("description", versionMetadata.get("description").asText());
        }
        if (versionMetadata.has("keywords") && versionMetadata.get("keywords").isArray()) {
            attributes.put("keywords", versionMetadata.get("keywords").toString());
        }
        if (versionMetadata.has("license")) {
            attributes.put("license", versionMetadata.get("license").asText());
        }
        component.setAttributes(attributes);
        component.setUpdatedAt(Instant.now());
        componentRepository.save(component);

        // Build the asset path
        String assetPath = buildAssetPath(namespace, name, version);

        // Create or update asset
        Instant now = Instant.now();
        try (Blob blob = storedBlob.get()) {
            AssetEntity asset = assetRepository
                    .findByRepositoryIdAndPath(repo.id(), assetPath)
                    .orElseGet(() -> {
                        var newAsset = new AssetEntity();
                        newAsset.setRepositoryId(repo.id());
                        newAsset.setPath(assetPath);
                        newAsset.setFormat(FORMAT);
                        newAsset.setCreatedAt(now);
                        return newAsset;
                    });

            asset.setComponentId(component.getId());
            asset.setBlobRef(blobRef.toExternalForm());
            asset.setContentType("application/gzip");
            asset.setSize(blob.properties().size());
            asset.setLastModified(now);
            asset.setUpdatedAt(now);
            asset.setCreatedBy(username);
            asset.setCreatedByIp(clientIp);

            Map<String, String> checksums = blob.properties().checksums();
            if (checksums != null) {
                asset.setChecksumMd5(checksums.get("md5"));
                asset.setChecksumSha1(checksums.get("sha1"));
                asset.setChecksumSha256(checksums.get("sha256"));
                asset.setChecksumSha512(checksums.get("sha512"));
            }

            assetRepository.save(asset);
        } catch (IOException e) {
            return new ErrorResponse(500, "Failed to process stored blob: " + e.getMessage());
        }

        log.info("Published npm package {}@{} to repository {}", packageName, version, repo.name());
        return new CreatedResponse(assetPath, Map.of());
    }

    private ComponentEntity findOrCreateComponent(
            RepositoryConfig repo, String namespace, String name, String version) {
        return componentRepository
                .findByRepositoryIdAndNamespaceAndNameAndVersion(repo.id(), namespace, name, version)
                .orElseGet(() -> {
                    var component = new ComponentEntity();
                    component.setRepositoryId(repo.id());
                    component.setFormat(FORMAT);
                    component.setNamespace(namespace);
                    component.setName(name);
                    component.setVersion(version);

                    Instant now = Instant.now();
                    component.setCreatedAt(now);
                    component.setUpdatedAt(now);
                    return componentRepository.save(component);
                });
    }

    private String buildAssetPath(String namespace, String name, String version) {
        if (namespace != null) {
            return namespace + "/" + name + "/-/" + name + "-" + version + ".tgz";
        }
        return "-/" + name + "-" + version + ".tgz";
    }
}
