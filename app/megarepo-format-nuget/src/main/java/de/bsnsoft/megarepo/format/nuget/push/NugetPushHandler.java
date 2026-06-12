package de.bsnsoft.megarepo.format.nuget.push;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import de.bsnsoft.megarepo.format.nuget.meta.NupkgReader;
import de.bsnsoft.megarepo.format.nuget.meta.NuspecMetadata;
import de.bsnsoft.megarepo.format.nuget.naming.NugetNames;
import de.bsnsoft.megarepo.storage.BlobStoreManager;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Handles {@code PUT /repository/{name}/api/v2/package} — the NuGet push
 * endpoint ({@code dotnet nuget push}). Despite the traditional V2 path this
 * is part of the V3 protocol (PackagePublish/2.0.0 resource).
 *
 * <p>The package is stored under the lowercase flat-container layout so that
 * subsequent restore requests hit the asset directly:
 * <pre>
 *   v3-flatcontainer/{id-lower}/{version-lower}/{id-lower}.{version-lower}.nupkg
 *   v3-flatcontainer/{id-lower}/{version-lower}/{id-lower}.nuspec
 * </pre>
 */
@Component
public class NugetPushHandler {

    private static final Logger log = LoggerFactory.getLogger(NugetPushHandler.class);
    private static final String FORMAT = "nuget";

    private final BlobStoreManager blobStoreManager;
    private final ComponentJpaRepository componentRepository;
    private final AssetJpaRepository assetRepository;
    private final NupkgReader nupkgReader;
    private final MultipartNupkgExtractor multipartExtractor;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public NugetPushHandler(
            BlobStoreManager blobStoreManager,
            ComponentJpaRepository componentRepository,
            AssetJpaRepository assetRepository,
            NupkgReader nupkgReader,
            MultipartNupkgExtractor multipartExtractor) {
        this.blobStoreManager = blobStoreManager;
        this.componentRepository = componentRepository;
        this.assetRepository = assetRepository;
        this.nupkgReader = nupkgReader;
        this.multipartExtractor = multipartExtractor;
    }

    public FormatResponse handlePush(RepositoryConfig repo, HttpServletRequest request) {
        byte[] nupkg = readPackageBytes(request);
        if (nupkg == null || nupkg.length == 0) {
            return new ErrorResponse(400, "Push request contains no package file");
        }

        return storePackage(repo, nupkg, request.getRemoteUser(), request.getRemoteAddr());
    }

    /**
     * Reads the package from the request. Multipart bodies may already have
     * been parsed by the container/framework (which consumes the raw input
     * stream), so the servlet Parts API is consulted first; if it is
     * unavailable for the method/container combination, the raw body is
     * parsed manually. Non-multipart bodies are taken verbatim.
     */
    private byte[] readPackageBytes(HttpServletRequest request) {
        String contentType = request.getContentType();
        boolean multipart = contentType != null
                && contentType.toLowerCase(java.util.Locale.ROOT).startsWith("multipart/");

        if (multipart) {
            try {
                for (jakarta.servlet.http.Part part : request.getParts()) {
                    if (part.getSize() > 0) {
                        try (java.io.InputStream in = part.getInputStream()) {
                            return in.readAllBytes();
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("Servlet Parts API unavailable for NuGet push ({}), parsing body manually",
                        e.getMessage());
            }
        }

        try {
            byte[] body = request.getInputStream().readAllBytes();
            return multipartExtractor.extract(contentType, body).orElse(null);
        } catch (IOException e) {
            log.warn("Failed to read NuGet push payload: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Stores a {@code .nupkg} — the shared core of {@code dotnet nuget push}
     * and manual uploads (Web-UI / REST). Service index, flat-container
     * version list, registrations and search are generated dynamically from
     * components/assets, so no metadata regeneration is needed.
     */
    public FormatResponse storePackage(RepositoryConfig repo, byte[] nupkgData, String username, String clientIp) {
        NupkgReader.NupkgContent content;
        try {
            content = nupkgReader.read(nupkgData);
        } catch (IOException e) {
            return new ErrorResponse(400, "Invalid NuGet package: " + e.getMessage());
        }

        NuspecMetadata metadata = content.metadata();
        String idLower = NugetNames.lowerId(metadata.id());
        String version = NugetNames.normalizeVersion(metadata.version());
        String versionLower = version.toLowerCase(java.util.Locale.ROOT);

        String basePath = "v3-flatcontainer/" + idLower + "/" + versionLower + "/";
        String nupkgPath = basePath + idLower + "." + versionLower + ".nupkg";
        String nuspecPath = basePath + idLower + ".nuspec";

        // NuGet semantics: re-pushing an existing version is a conflict
        Optional<AssetEntity> existing = assetRepository.findByRepositoryIdAndPath(repo.id(), nupkgPath);
        if (existing.isPresent() && existing.get().getBlobRef() != null) {
            return new ErrorResponse(409,
                    "Package %s %s already exists in repository %s".formatted(metadata.id(), version, repo.name()));
        }

        BlobStore blobStore = blobStoreManager.get(repo.blobStoreName());

        BlobRef nupkgRef = blobStore.store(
                new ByteArrayInputStream(nupkgData), nupkgData.length, Map.of("Content-Type", "application/zip"));
        byte[] nuspecBytes = content.nuspecBytes();
        BlobRef nuspecRef = blobStore.store(
                new ByteArrayInputStream(nuspecBytes), nuspecBytes.length, Map.of("Content-Type", "application/xml"));

        ComponentEntity component = findOrCreateComponent(repo, idLower, version);
        Map<String, Object> attributes = new HashMap<>(component.getAttributes());
        attributes.put("originalId", metadata.id());
        attributes.put("originalVersion", metadata.version());
        if (metadata.description() != null) {
            attributes.put("description", metadata.description());
        }
        if (metadata.authors() != null) {
            attributes.put("authors", metadata.authors());
        }
        attributes.put("dependencies", dependenciesJson(metadata));
        component.setAttributes(attributes);
        component.setUpdatedAt(Instant.now());
        componentRepository.save(component);

        FormatResponse nupkgResult = saveAsset(
                repo, component, nupkgPath, nupkgRef, "application/zip", blobStore, username, clientIp);
        if (nupkgResult instanceof ErrorResponse) {
            return nupkgResult;
        }
        FormatResponse nuspecResult = saveAsset(
                repo, component, nuspecPath, nuspecRef, "application/xml", blobStore, username, clientIp);
        if (nuspecResult instanceof ErrorResponse) {
            return nuspecResult;
        }

        log.info("Pushed NuGet package {} {} to repository {}", metadata.id(), version, repo.name());
        return new CreatedResponse(nupkgPath, Map.of());
    }

    private String dependenciesJson(NuspecMetadata metadata) {
        ArrayNode list = objectMapper.createArrayNode();
        for (NuspecMetadata.Dependency dependency : metadata.dependencies()) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("id", dependency.id());
            node.put("range", dependency.versionRange() == null ? "" : dependency.versionRange());
            node.put("targetFramework", dependency.targetFramework() == null ? "" : dependency.targetFramework());
            list.add(node);
        }
        return list.toString();
    }

    private FormatResponse saveAsset(
            RepositoryConfig repo,
            ComponentEntity component,
            String path,
            BlobRef blobRef,
            String contentType,
            BlobStore blobStore,
            String username,
            String clientIp) {
        Optional<Blob> storedBlob = blobStore.get(blobRef);
        if (storedBlob.isEmpty()) {
            return new ErrorResponse(500, "Failed to store blob for: " + path);
        }

        Instant now = Instant.now();
        try (Blob blob = storedBlob.get()) {
            AssetEntity asset = assetRepository
                    .findByRepositoryIdAndPath(repo.id(), path)
                    .orElseGet(() -> {
                        var newAsset = new AssetEntity();
                        newAsset.setRepositoryId(repo.id());
                        newAsset.setPath(path);
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
            return new CreatedResponse(path, Map.of());
        } catch (IOException e) {
            return new ErrorResponse(500, "Failed to process stored blob: " + e.getMessage());
        }
    }

    private ComponentEntity findOrCreateComponent(RepositoryConfig repo, String idLower, String version) {
        return componentRepository
                .findByRepositoryIdAndNamespaceAndNameAndVersion(repo.id(), null, idLower, version)
                .orElseGet(() -> {
                    var component = new ComponentEntity();
                    component.setRepositoryId(repo.id());
                    component.setFormat(FORMAT);
                    component.setNamespace(null);
                    component.setName(idLower);
                    component.setVersion(version);

                    Instant now = Instant.now();
                    component.setCreatedAt(now);
                    component.setUpdatedAt(now);
                    return componentRepository.save(component);
                });
    }
}
