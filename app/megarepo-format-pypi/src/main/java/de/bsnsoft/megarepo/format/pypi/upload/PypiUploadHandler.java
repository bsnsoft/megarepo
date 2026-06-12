package de.bsnsoft.megarepo.format.pypi.upload;

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
import de.bsnsoft.megarepo.format.pypi.naming.PythonNameNormalizer;
import de.bsnsoft.megarepo.storage.BlobStoreManager;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.Part;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Handles PyPI upload requests (twine/pip upload).
 * Parses multipart form data with fields: :action, name, version, content (file).
 */
@Component
public class PypiUploadHandler {

    private static final Logger log = LoggerFactory.getLogger(PypiUploadHandler.class);
    private static final String FORMAT = "pypi";

    private final BlobStoreManager blobStoreManager;
    private final ComponentJpaRepository componentRepository;
    private final AssetJpaRepository assetRepository;
    private final PythonNameNormalizer nameNormalizer;

    public PypiUploadHandler(
            BlobStoreManager blobStoreManager,
            ComponentJpaRepository componentRepository,
            AssetJpaRepository assetRepository,
            PythonNameNormalizer nameNormalizer) {
        this.blobStoreManager = blobStoreManager;
        this.componentRepository = componentRepository;
        this.assetRepository = assetRepository;
        this.nameNormalizer = nameNormalizer;
    }

    public FormatResponse handleUpload(RepositoryConfig repo, HttpServletRequest request) {
        try {
            // Parse multipart fields
            String name = getPartValue(request, "name");
            String version = getPartValue(request, "version");
            Part filePart = request.getPart("content");

            if (name == null || name.isBlank()) {
                return new ErrorResponse(400, "Missing required field: name");
            }
            if (version == null || version.isBlank()) {
                return new ErrorResponse(400, "Missing required field: version");
            }
            if (filePart == null) {
                return new ErrorResponse(400, "Missing required field: content");
            }

            String contentType = filePart.getContentType() != null
                    ? filePart.getContentType()
                    : "application/octet-stream";

            return storeDistribution(
                    repo,
                    name,
                    version,
                    filePart.getSubmittedFileName(),
                    filePart.getInputStream(),
                    filePart.getSize(),
                    contentType,
                    request.getRemoteUser(),
                    request.getRemoteAddr());

        } catch (IOException | ServletException e) {
            log.error("Failed to handle PyPI upload: {}", e.getMessage(), e);
            return new ErrorResponse(500, "Failed to process upload: " + e.getMessage());
        }
    }

    /**
     * Stores a PyPI distribution file — the shared core of both twine uploads
     * (multipart POST) and manual uploads (Web-UI / REST). The simple index is
     * generated dynamically from components/assets, so no metadata
     * regeneration is needed.
     */
    public FormatResponse storeDistribution(
            RepositoryConfig repo,
            String name,
            String version,
            String filename,
            InputStream fileStream,
            long fileSize,
            String contentType,
            String username,
            String clientIp) {
        String normalizedName = nameNormalizer.normalize(name);
        if (filename == null || filename.isBlank()) {
            filename = normalizedName + "-" + version + ".tar.gz";
        }

        String assetPath = "packages/" + filename;

        // Store blob
        BlobStore blobStore = blobStoreManager.get(repo.blobStoreName());

        BlobRef blobRef;
        Map<String, String> headers = Map.of("Content-Type", contentType);

        if (fileSize > 0) {
            blobRef = blobStore.store(fileStream, fileSize, headers);
        } else {
            blobRef = blobStore.store(fileStream, headers);
        }

        Optional<Blob> storedBlob = blobStore.get(blobRef);
        if (storedBlob.isEmpty()) {
            return new ErrorResponse(500, "Failed to store blob");
        }

        // Create component
        ComponentEntity component = findOrCreateComponent(repo, normalizedName, version);

        // Create asset
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
            asset.setContentType(contentType);
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

        log.debug("Uploaded PyPI package: {}=={} to {}", normalizedName, version, assetPath);
        return new CreatedResponse(assetPath, Map.of());
    }

    private String getPartValue(HttpServletRequest request, String name)
            throws IOException, ServletException {
        Part part = request.getPart(name);
        if (part == null) {
            return null;
        }
        return new String(part.getInputStream().readAllBytes()).trim();
    }

    private ComponentEntity findOrCreateComponent(RepositoryConfig repo, String name, String version) {
        return componentRepository
                .findByRepositoryIdAndNamespaceAndNameAndVersion(repo.id(), null, name, version)
                .orElseGet(() -> {
                    var component = new ComponentEntity();
                    component.setRepositoryId(repo.id());
                    component.setFormat(FORMAT);
                    component.setNamespace(null);
                    component.setName(name);
                    component.setVersion(version);

                    Instant now = Instant.now();
                    component.setCreatedAt(now);
                    component.setUpdatedAt(now);
                    return componentRepository.save(component);
                });
    }
}
