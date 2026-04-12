package de.bsnsoft.megarepo.repository.proxy;

import de.bsnsoft.megarepo.core.repository.RepositoryConfig;
import de.bsnsoft.megarepo.core.repository.RepositoryType;
import de.bsnsoft.megarepo.database.entity.AssetEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ProxyCacheCheckerTest {

    private ProxyCacheChecker checker;

    @BeforeEach
    void setUp() {
        checker = new ProxyCacheChecker();
    }

    @Test
    void assetNotExpired_withinTtl_returnsFalse() {
        AssetEntity asset = createAsset(Instant.now().minus(10, ChronoUnit.MINUTES));
        RepositoryConfig repo = createProxyRepo(Map.of("proxy", Map.of("contentMaxAge", 60)));

        assertFalse(checker.isExpired(asset, repo));
    }

    @Test
    void assetExpired_pastTtl_returnsTrue() {
        AssetEntity asset = createAsset(Instant.now().minus(120, ChronoUnit.MINUTES));
        RepositoryConfig repo = createProxyRepo(Map.of("proxy", Map.of("contentMaxAge", 60)));

        assertTrue(checker.isExpired(asset, repo));
    }

    @Test
    void defaultTtl_whenConfigMissing_uses1440Minutes() {
        AssetEntity asset = createAsset(Instant.now().minus(1000, ChronoUnit.MINUTES));
        RepositoryConfig repo = createProxyRepo(Map.of());

        // 1000 < 1440 default, so not expired
        assertFalse(checker.isExpired(asset, repo));
    }

    @Test
    void defaultTtl_whenConfigMissing_expiredAfter1440Minutes() {
        AssetEntity asset = createAsset(Instant.now().minus(1500, ChronoUnit.MINUTES));
        RepositoryConfig repo = createProxyRepo(Map.of());

        // 1500 > 1440 default, so expired
        assertTrue(checker.isExpired(asset, repo));
    }

    @Test
    void metadataPath_usesMetadataMaxAge() {
        AssetEntity asset = createAsset(Instant.now().minus(20, ChronoUnit.MINUTES));
        RepositoryConfig repo = createProxyRepo(
                Map.of("proxy", Map.of("contentMaxAge", 1440, "metadataMaxAge", 10)));

        // Content would not be expired (20 < 1440), but metadata is expired (20 > 10)
        assertFalse(checker.isExpired(asset, repo));
        assertTrue(checker.isMetadataExpired(asset, repo));
    }

    @Test
    void metadataNotExpired_withinMetadataMaxAge() {
        AssetEntity asset = createAsset(Instant.now().minus(3, ChronoUnit.MINUTES));
        RepositoryConfig repo = createProxyRepo(Map.of());

        // Default metadata TTL is 5 minutes, 3 < 5 so not expired
        assertFalse(checker.isMetadataExpired(asset, repo));
    }

    @Test
    void metadataExpired_pastDefaultTtl() {
        AssetEntity asset = createAsset(Instant.now().minus(10, ChronoUnit.MINUTES));
        RepositoryConfig repo = createProxyRepo(Map.of());

        // Default metadata TTL is 5 minutes, 10 > 5 so expired
        assertTrue(checker.isMetadataExpired(asset, repo));
    }

    @Test
    void nullLastModified_treatedAsExpired() {
        AssetEntity asset = createAsset(null);
        RepositoryConfig repo = createProxyRepo(Map.of("proxy", Map.of("contentMaxAge", 60)));

        assertTrue(checker.isExpired(asset, repo));
    }

    @Test
    void getContentMaxAge_returnsConfiguredValue() {
        RepositoryConfig repo = createProxyRepo(Map.of("proxy", Map.of("contentMaxAge", 720)));
        assertEquals(720, checker.getContentMaxAge(repo));
    }

    @Test
    void getMetadataMaxAge_returnsDefaultWhenNotConfigured() {
        RepositoryConfig repo = createProxyRepo(Map.of());
        assertEquals(5, checker.getMetadataMaxAge(repo));
    }

    // --- New attribute names: cacheTtlMinutes / metadataCacheTtlMinutes ---

    @Test
    void cacheTtlMinutes_preferredOverContentMaxAge() {
        RepositoryConfig repo = createProxyRepo(
                Map.of("proxy", Map.of("cacheTtlMinutes", 60, "contentMaxAge", 720)));
        // cacheTtlMinutes takes precedence
        assertEquals(60, checker.getContentMaxAge(repo));
    }

    @Test
    void cacheTtlMinutes_usedWhenSet() {
        AssetEntity asset = createAsset(Instant.now().minus(30, ChronoUnit.MINUTES));
        RepositoryConfig repo = createProxyRepo(Map.of("proxy", Map.of("cacheTtlMinutes", 60)));

        // 30 < 60, not expired
        assertFalse(checker.isExpired(asset, repo));
    }

    @Test
    void cacheTtlMinutes_expired() {
        AssetEntity asset = createAsset(Instant.now().minus(90, ChronoUnit.MINUTES));
        RepositoryConfig repo = createProxyRepo(Map.of("proxy", Map.of("cacheTtlMinutes", 60)));

        // 90 > 60, expired
        assertTrue(checker.isExpired(asset, repo));
    }

    @Test
    void metadataCacheTtlMinutes_preferredOverMetadataMaxAge() {
        RepositoryConfig repo = createProxyRepo(
                Map.of("proxy", Map.of("metadataCacheTtlMinutes", 10, "metadataMaxAge", 30)));
        // metadataCacheTtlMinutes takes precedence
        assertEquals(10, checker.getMetadataMaxAge(repo));
    }

    @Test
    void metadataCacheTtlMinutes_usedWhenSet() {
        AssetEntity asset = createAsset(Instant.now().minus(3, ChronoUnit.MINUTES));
        RepositoryConfig repo = createProxyRepo(Map.of("proxy", Map.of("metadataCacheTtlMinutes", 10)));

        // 3 < 10, not expired
        assertFalse(checker.isMetadataExpired(asset, repo));
    }

    @Test
    void contentMaxAge_backwardCompatible() {
        // When only contentMaxAge is set (no cacheTtlMinutes), it should still work
        RepositoryConfig repo = createProxyRepo(Map.of("proxy", Map.of("contentMaxAge", 120)));
        assertEquals(120, checker.getContentMaxAge(repo));
    }

    @Test
    void metadataMaxAge_backwardCompatible() {
        // When only metadataMaxAge is set (no metadataCacheTtlMinutes), it should still work
        RepositoryConfig repo = createProxyRepo(Map.of("proxy", Map.of("metadataMaxAge", 30)));
        assertEquals(30, checker.getMetadataMaxAge(repo));
    }

    private RepositoryConfig createProxyRepo(Map<String, Object> attributes) {
        return new RepositoryConfig(
                UUID.randomUUID(), "proxy-repo", "maven2", RepositoryType.PROXY, true, "default", attributes);
    }

    private AssetEntity createAsset(Instant lastModified) {
        var asset = new AssetEntity();
        asset.setId(UUID.randomUUID());
        asset.setRepositoryId(UUID.randomUUID());
        asset.setFormat("maven2");
        asset.setPath("com/example/artifact/1.0/artifact-1.0.jar");
        asset.setLastModified(lastModified);
        asset.setCreatedAt(Instant.now());
        asset.setUpdatedAt(Instant.now());
        return asset;
    }
}
