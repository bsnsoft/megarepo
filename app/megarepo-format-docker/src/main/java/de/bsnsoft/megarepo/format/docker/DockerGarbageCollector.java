package de.bsnsoft.megarepo.format.docker;

import de.bsnsoft.megarepo.core.storage.BlobRef;
import de.bsnsoft.megarepo.database.entity.AssetEntity;
import de.bsnsoft.megarepo.database.repository.AssetJpaRepository;
import de.bsnsoft.megarepo.database.repository.RepositoryJpaRepository;
import de.bsnsoft.megarepo.storage.BlobStoreManager;
import de.bsnsoft.megarepo.tasks.TaskRunner;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Garbage collector for Docker repositories.
 *
 * <p>Finds blob assets (layers and configs) that are not referenced by any manifest
 * and deletes them from both the blob store and the database. This handles orphaned
 * blobs that remain after manifest deletion.
 *
 * <p>Registered as task type {@code docker.gc} in the task framework.
 */
@Component
public class DockerGarbageCollector {

    private static final Logger log = LoggerFactory.getLogger(DockerGarbageCollector.class);
    private static final String TASK_TYPE = "docker.gc";
    private static final String FORMAT = "docker";

    private final AssetJpaRepository assetRepository;
    private final RepositoryJpaRepository repositoryRepository;
    private final BlobStoreManager blobStoreManager;
    private final TaskRunner taskRunner;
    private final ObjectMapper objectMapper;

    public DockerGarbageCollector(
            AssetJpaRepository assetRepository,
            RepositoryJpaRepository repositoryRepository,
            BlobStoreManager blobStoreManager,
            TaskRunner taskRunner) {
        this.assetRepository = assetRepository;
        this.repositoryRepository = repositoryRepository;
        this.blobStoreManager = blobStoreManager;
        this.taskRunner = taskRunner;
        this.objectMapper = new ObjectMapper();
    }

    @PostConstruct
    void register() {
        taskRunner.registerHandler(TASK_TYPE, this::execute);
    }

    @Transactional
    public void execute() {
        log.info("Starting Docker garbage collection");

        var dockerRepos = repositoryRepository.findByFormat(FORMAT);
        if (dockerRepos.isEmpty()) {
            log.info("No Docker repositories found, skipping GC");
            return;
        }

        int totalDeleted = 0;

        for (var repo : dockerRepos) {
            int deleted = collectGarbage(repo.getId(), repo.getName(), repo.getBlobStoreName());
            totalDeleted += deleted;
        }

        log.info("Docker GC complete: deleted {} orphaned blob(s) across {} repository(ies)",
                totalDeleted, dockerRepos.size());
    }

    private int collectGarbage(UUID repoId, String repoName, String blobStoreName) {
        // Step 1: Find all manifest assets and collect their referenced digests
        Set<String> referencedDigests = collectReferencedDigests(repoId);

        // Step 2: Find all blob assets in this repo
        List<AssetEntity> blobAssets = assetRepository.findByRepositoryIdAndPathStartingWith(
                repoId, "v2/");

        // Filter to only blob assets (not manifests, not tags lists, etc.)
        List<AssetEntity> candidateBlobs = blobAssets.stream()
                .filter(a -> a.getPath().contains("/blobs/"))
                .toList();

        if (candidateBlobs.isEmpty()) {
            log.debug("No blob assets found in Docker repo '{}'", repoName);
            return 0;
        }

        // Step 3: Delete blobs not referenced by any manifest
        int deleted = 0;
        for (var blobAsset : candidateBlobs) {
            String digest = extractDigestFromPath(blobAsset.getPath());
            if (digest == null) {
                continue;
            }

            if (!referencedDigests.contains(digest)) {
                deleteOrphanedBlob(blobAsset, blobStoreName);
                deleted++;
            }
        }

        if (deleted > 0) {
            log.info("Docker GC for repo '{}': deleted {} orphaned blob(s)", repoName, deleted);
        } else {
            log.debug("Docker GC for repo '{}': no orphaned blobs found", repoName);
        }

        return deleted;
    }

    /**
     * Collects all blob digests referenced by manifests in a repository.
     * Parses each manifest to find layer and config digests.
     * Also handles manifest lists by including referenced child manifest digests.
     */
    private Set<String> collectReferencedDigests(UUID repoId) {
        Set<String> referencedDigests = new HashSet<>();

        List<AssetEntity> manifestAssets = assetRepository.findByRepositoryIdAndPathStartingWith(
                repoId, "v2/");

        List<AssetEntity> manifests = manifestAssets.stream()
                .filter(a -> a.getPath().contains("/manifests/"))
                .toList();

        for (var manifestAsset : manifests) {
            if (manifestAsset.getBlobRef() == null) {
                continue;
            }

            try {
                BlobRef blobRef = BlobRef.parse(manifestAsset.getBlobRef());
                var blobStore = blobStoreManager.get(blobRef.blobStoreName());
                var blobOpt = blobStore.get(blobRef);

                if (blobOpt.isEmpty()) {
                    continue;
                }

                try (var blob = blobOpt.get()) {
                    byte[] manifestBytes = blob.inputStream().readAllBytes();
                    JsonNode root = objectMapper.readTree(manifestBytes);

                    String mediaType = manifestAsset.getContentType();
                    if (mediaType == null && root.has("mediaType")) {
                        mediaType = root.get("mediaType").asText();
                    }

                    if (isManifestList(mediaType)) {
                        // Manifest list: references child manifests by digest
                        extractManifestListDigests(root, referencedDigests);
                    } else {
                        // Regular manifest: references config and layers
                        extractManifestDigests(root, referencedDigests);
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to parse manifest {} for GC: {}",
                        manifestAsset.getPath(), e.getMessage());
            }
        }

        return referencedDigests;
    }

    private boolean isManifestList(String mediaType) {
        if (mediaType == null) {
            return false;
        }
        return mediaType.equals("application/vnd.docker.distribution.manifest.list.v2+json")
                || mediaType.equals("application/vnd.oci.image.index.v1+json");
    }

    /**
     * Extracts config and layer digests from a regular Docker/OCI manifest.
     */
    private void extractManifestDigests(JsonNode root, Set<String> digests) {
        // Config digest
        if (root.has("config") && root.get("config").has("digest")) {
            digests.add(root.get("config").get("digest").asText());
        }

        // Layer digests
        if (root.has("layers")) {
            for (JsonNode layer : root.get("layers")) {
                if (layer.has("digest")) {
                    digests.add(layer.get("digest").asText());
                }
            }
        }
    }

    /**
     * Extracts child manifest digests from a manifest list / OCI index.
     */
    private void extractManifestListDigests(JsonNode root, Set<String> digests) {
        if (root.has("manifests")) {
            for (JsonNode manifest : root.get("manifests")) {
                if (manifest.has("digest")) {
                    digests.add(manifest.get("digest").asText());
                }
            }
        }
    }

    /**
     * Extracts the digest (e.g., "sha256:abc123") from a blob asset path.
     * Path format: v2/{imageName}/blobs/{digest}
     */
    private String extractDigestFromPath(String path) {
        int blobsIdx = path.indexOf("/blobs/");
        if (blobsIdx < 0) {
            return null;
        }
        return path.substring(blobsIdx + "/blobs/".length());
    }

    private void deleteOrphanedBlob(AssetEntity asset, String blobStoreName) {
        if (asset.getBlobRef() != null) {
            try {
                BlobRef blobRef = BlobRef.parse(asset.getBlobRef());
                var blobStore = blobStoreManager.get(blobRef.blobStoreName());
                blobStore.delete(blobRef);
            } catch (Exception e) {
                log.warn("Failed to delete blob {} for orphaned asset {}: {}",
                        asset.getBlobRef(), asset.getPath(), e.getMessage());
            }
        }

        assetRepository.delete(asset);
        log.debug("Deleted orphaned Docker blob: {}", asset.getPath());
    }
}
