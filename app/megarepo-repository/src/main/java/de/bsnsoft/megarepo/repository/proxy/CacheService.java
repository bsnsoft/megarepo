package de.bsnsoft.megarepo.repository.proxy;

import de.bsnsoft.megarepo.core.repository.RepositoryConfig;
import de.bsnsoft.megarepo.core.storage.BlobRef;
import de.bsnsoft.megarepo.core.storage.BlobStore;
import de.bsnsoft.megarepo.database.entity.AssetEntity;
import de.bsnsoft.megarepo.database.repository.AssetJpaRepository;
import de.bsnsoft.megarepo.database.repository.NegativeCacheJpaRepository;
import de.bsnsoft.megarepo.storage.BlobStoreManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class CacheService {

    private static final Logger log = LoggerFactory.getLogger(CacheService.class);

    private final AssetJpaRepository assetRepository;
    private final NegativeCacheJpaRepository negativeCacheRepository;
    private final BlobStoreManager blobStoreManager;

    public CacheService(
            AssetJpaRepository assetRepository,
            NegativeCacheJpaRepository negativeCacheRepository,
            BlobStoreManager blobStoreManager) {
        this.assetRepository = assetRepository;
        this.negativeCacheRepository = negativeCacheRepository;
        this.blobStoreManager = blobStoreManager;
    }

    public CacheInfo getCacheInfo(RepositoryConfig repo) {
        long cachedArtifacts = assetRepository.countByRepositoryId(repo.id());
        Long totalSize = assetRepository.sumSizeByRepositoryId(repo.id());
        long totalSizeBytes = totalSize != null ? totalSize : 0L;
        long negativeCacheEntries = negativeCacheRepository.countByRepositoryId(repo.id());
        Instant oldestEntry = assetRepository.findOldestCreatedAtByRepositoryId(repo.id());
        Instant newestEntry = assetRepository.findNewestCreatedAtByRepositoryId(repo.id());

        return new CacheInfo(
                repo.name(), cachedArtifacts, totalSizeBytes, negativeCacheEntries, oldestEntry, newestEntry);
    }

    public Page<AssetEntity> getCachedAssets(UUID repoId, Pageable pageable) {
        return assetRepository.findByRepositoryId(repoId, pageable);
    }

    @Transactional
    public boolean invalidateAsset(RepositoryConfig repo, String path) {
        var maybeAsset = assetRepository.findByRepositoryIdAndPath(repo.id(), path);
        if (maybeAsset.isEmpty()) {
            return false;
        }
        AssetEntity asset = maybeAsset.get();
        deleteBlob(repo, asset);
        assetRepository.delete(asset);
        log.info("Invalidated cached asset for repo={} path={}", repo.name(), path);
        return true;
    }

    @Transactional
    public int invalidateAll(RepositoryConfig repo) {
        List<AssetEntity> assets = assetRepository.findAllByRepositoryId(repo.id());
        int count = assets.size();
        for (AssetEntity asset : assets) {
            deleteBlob(repo, asset);
        }
        assetRepository.deleteAll(assets);
        negativeCacheRepository.deleteByRepositoryId(repo.id());
        log.info("Invalidated all {} cached assets and negative cache entries for repo={}", count, repo.name());
        return count;
    }

    @Transactional
    public int invalidateByPattern(RepositoryConfig repo, String patternStr) {
        Pattern pattern = Pattern.compile(patternStr);
        List<AssetEntity> assets = assetRepository.findAllByRepositoryId(repo.id());
        List<AssetEntity> matching =
                assets.stream().filter(a -> pattern.matcher(a.getPath()).matches()).toList();
        for (AssetEntity asset : matching) {
            deleteBlob(repo, asset);
        }
        assetRepository.deleteAll(matching);
        log.info(
                "Invalidated {} cached assets matching pattern '{}' for repo={}",
                matching.size(),
                patternStr,
                repo.name());
        return matching.size();
    }

    private void deleteBlob(RepositoryConfig repo, AssetEntity asset) {
        if (asset.getBlobRef() != null) {
            try {
                BlobRef blobRef = BlobRef.parse(asset.getBlobRef());
                BlobStore blobStore = blobStoreManager.get(repo.blobStoreName());
                blobStore.delete(blobRef);
            } catch (Exception e) {
                log.warn(
                        "Failed to delete blob for repo={} path={}: {}",
                        repo.name(),
                        asset.getPath(),
                        e.getMessage());
            }
        }
    }

    public record CacheInfo(
            String repository,
            long cachedArtifacts,
            long totalSizeBytes,
            long negativeCacheEntries,
            Instant oldestEntry,
            Instant newestEntry) {}
}
