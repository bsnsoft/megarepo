package de.bsnsoft.megarepo.repository.proxy;

import de.bsnsoft.megarepo.core.repository.RepositoryConfig;
import de.bsnsoft.megarepo.core.repository.RepositoryType;
import de.bsnsoft.megarepo.core.storage.BlobRef;
import de.bsnsoft.megarepo.core.storage.BlobStore;
import de.bsnsoft.megarepo.database.entity.AssetEntity;
import de.bsnsoft.megarepo.database.repository.AssetJpaRepository;
import de.bsnsoft.megarepo.database.repository.NegativeCacheJpaRepository;
import de.bsnsoft.megarepo.repository.proxy.CacheService.CacheInfo;
import de.bsnsoft.megarepo.storage.BlobStoreManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CacheServiceTest {

    @Mock
    private AssetJpaRepository assetRepository;

    @Mock
    private NegativeCacheJpaRepository negativeCacheRepository;

    @Mock
    private BlobStoreManager blobStoreManager;

    @Mock
    private BlobStore blobStore;

    private CacheService service;

    private static final UUID REPO_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new CacheService(assetRepository, negativeCacheRepository, blobStoreManager);
    }

    @Test
    void getCacheInfo_returnsAggregatedData() {
        RepositoryConfig repo = createProxyRepo();
        Instant oldest = Instant.parse("2026-01-01T00:00:00Z");
        Instant newest = Instant.parse("2026-03-28T12:00:00Z");

        when(assetRepository.countByRepositoryId(REPO_ID)).thenReturn(42L);
        when(assetRepository.sumSizeByRepositoryId(REPO_ID)).thenReturn(1048576L);
        when(negativeCacheRepository.countByRepositoryId(REPO_ID)).thenReturn(5L);
        when(assetRepository.findOldestCreatedAtByRepositoryId(REPO_ID)).thenReturn(oldest);
        when(assetRepository.findNewestCreatedAtByRepositoryId(REPO_ID)).thenReturn(newest);

        CacheInfo info = service.getCacheInfo(repo);

        assertEquals("proxy-repo", info.repository());
        assertEquals(42L, info.cachedArtifacts());
        assertEquals(1048576L, info.totalSizeBytes());
        assertEquals(5L, info.negativeCacheEntries());
        assertEquals(oldest, info.oldestEntry());
        assertEquals(newest, info.newestEntry());
    }

    @Test
    void getCacheInfo_emptyRepo_returnsZeros() {
        RepositoryConfig repo = createProxyRepo();

        when(assetRepository.countByRepositoryId(REPO_ID)).thenReturn(0L);
        when(assetRepository.sumSizeByRepositoryId(REPO_ID)).thenReturn(null);
        when(negativeCacheRepository.countByRepositoryId(REPO_ID)).thenReturn(0L);
        when(assetRepository.findOldestCreatedAtByRepositoryId(REPO_ID)).thenReturn(null);
        when(assetRepository.findNewestCreatedAtByRepositoryId(REPO_ID)).thenReturn(null);

        CacheInfo info = service.getCacheInfo(repo);

        assertEquals(0L, info.cachedArtifacts());
        assertEquals(0L, info.totalSizeBytes());
        assertEquals(0L, info.negativeCacheEntries());
        assertNull(info.oldestEntry());
        assertNull(info.newestEntry());
    }

    @Test
    void invalidateAsset_existingAsset_deletesAndReturnsTrue() {
        RepositoryConfig repo = createProxyRepo();
        String path = "com/example/artifact-1.0.jar";
        AssetEntity asset = createAsset(path, "default@blob-123");

        when(assetRepository.findByRepositoryIdAndPath(REPO_ID, path)).thenReturn(Optional.of(asset));
        when(blobStoreManager.get("default")).thenReturn(blobStore);
        when(blobStore.delete(any(BlobRef.class))).thenReturn(true);

        boolean result = service.invalidateAsset(repo, path);

        assertTrue(result);
        verify(assetRepository).delete(asset);
        verify(blobStore).delete(new BlobRef("default", "blob-123"));
    }

    @Test
    void invalidateAsset_notFound_returnsFalse() {
        RepositoryConfig repo = createProxyRepo();
        String path = "com/example/nonexistent.jar";

        when(assetRepository.findByRepositoryIdAndPath(REPO_ID, path)).thenReturn(Optional.empty());

        boolean result = service.invalidateAsset(repo, path);

        assertFalse(result);
        verify(assetRepository, never()).delete(any(AssetEntity.class));
    }

    @Test
    void invalidateAll_deletesAllAssetsAndNegativeCache() {
        RepositoryConfig repo = createProxyRepo();
        AssetEntity asset1 = createAsset("path1.jar", "default@blob-1");
        AssetEntity asset2 = createAsset("path2.jar", "default@blob-2");

        when(assetRepository.findAllByRepositoryId(REPO_ID)).thenReturn(List.of(asset1, asset2));
        when(blobStoreManager.get("default")).thenReturn(blobStore);
        when(blobStore.delete(any(BlobRef.class))).thenReturn(true);

        int count = service.invalidateAll(repo);

        assertEquals(2, count);
        verify(assetRepository).deleteAll(List.of(asset1, asset2));
        verify(negativeCacheRepository).deleteByRepositoryId(REPO_ID);
        verify(blobStore).delete(new BlobRef("default", "blob-1"));
        verify(blobStore).delete(new BlobRef("default", "blob-2"));
    }

    @Test
    void invalidateAll_emptyRepo_returnsZero() {
        RepositoryConfig repo = createProxyRepo();

        when(assetRepository.findAllByRepositoryId(REPO_ID)).thenReturn(List.of());

        int count = service.invalidateAll(repo);

        assertEquals(0, count);
        verify(negativeCacheRepository).deleteByRepositoryId(REPO_ID);
    }

    @Test
    void invalidateByPattern_matchingAssets_deleted() {
        RepositoryConfig repo = createProxyRepo();
        AssetEntity snapshot1 = createAsset("com/example/1.0-SNAPSHOT/artifact.jar", "default@blob-s1");
        AssetEntity release1 = createAsset("com/example/1.0/artifact.jar", "default@blob-r1");
        AssetEntity snapshot2 = createAsset("com/other/2.0-SNAPSHOT/other.jar", "default@blob-s2");

        when(assetRepository.findAllByRepositoryId(REPO_ID))
                .thenReturn(List.of(snapshot1, release1, snapshot2));
        when(blobStoreManager.get("default")).thenReturn(blobStore);
        when(blobStore.delete(any(BlobRef.class))).thenReturn(true);

        int count = service.invalidateByPattern(repo, ".*SNAPSHOT.*");

        assertEquals(2, count);
        verify(blobStore).delete(new BlobRef("default", "blob-s1"));
        verify(blobStore).delete(new BlobRef("default", "blob-s2"));
        verify(blobStore, never()).delete(new BlobRef("default", "blob-r1"));
    }

    @Test
    void invalidateByPattern_noMatches_returnsZero() {
        RepositoryConfig repo = createProxyRepo();
        AssetEntity release = createAsset("com/example/1.0/artifact.jar", "default@blob-r1");

        when(assetRepository.findAllByRepositoryId(REPO_ID)).thenReturn(List.of(release));

        int count = service.invalidateByPattern(repo, ".*SNAPSHOT.*");

        assertEquals(0, count);
    }

    @Test
    void invalidateAsset_blobDeleteFails_stillDeletesAsset() {
        RepositoryConfig repo = createProxyRepo();
        String path = "com/example/artifact-1.0.jar";
        AssetEntity asset = createAsset(path, "default@blob-123");

        when(assetRepository.findByRepositoryIdAndPath(REPO_ID, path)).thenReturn(Optional.of(asset));
        when(blobStoreManager.get("default")).thenThrow(new IllegalArgumentException("Blob store not found"));

        boolean result = service.invalidateAsset(repo, path);

        assertTrue(result);
        verify(assetRepository).delete(asset);
    }

    private RepositoryConfig createProxyRepo() {
        return new RepositoryConfig(
                REPO_ID,
                "proxy-repo",
                "maven2",
                RepositoryType.PROXY,
                true,
                "default",
                Map.of("proxy", Map.of("remoteUrl", "https://repo.maven.apache.org/maven2")));
    }

    private AssetEntity createAsset(String path, String blobRef) {
        var asset = new AssetEntity();
        asset.setId(UUID.randomUUID());
        asset.setRepositoryId(REPO_ID);
        asset.setPath(path);
        asset.setFormat("maven2");
        asset.setBlobRef(blobRef);
        asset.setSize(1024L);
        asset.setContentType("application/java-archive");
        asset.setLastModified(Instant.now());
        asset.setCreatedAt(Instant.now());
        asset.setUpdatedAt(Instant.now());
        return asset;
    }
}
