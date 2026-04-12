package de.bsnsoft.megarepo.tasks.cleanup;

import de.bsnsoft.megarepo.database.entity.AssetEntity;
import de.bsnsoft.megarepo.database.entity.CleanupPolicyEntity;
import de.bsnsoft.megarepo.database.entity.ComponentEntity;
import de.bsnsoft.megarepo.database.repository.ComponentJpaRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CleanupPolicyEvaluatorTest {

    private final CleanupPolicyEvaluator evaluator = new CleanupPolicyEvaluator();

    @Mock
    private ComponentJpaRepository componentRepository;

    // --- shouldDelete tests ---

    @Test
    void shouldDelete_emptyCriteria_returnsFalse() {
        var policy = createPolicy(Map.of());
        var asset = createAsset("com/example/lib/1.0/lib-1.0.jar");
        assertFalse(evaluator.shouldDelete(policy, asset));
    }

    @Test
    void shouldDelete_nullCriteria_returnsFalse() {
        var policy = createPolicy(null);
        var asset = createAsset("com/example/lib/1.0/lib-1.0.jar");
        assertFalse(evaluator.shouldDelete(policy, asset));
    }

    @Test
    void shouldDelete_lastBlobUpdated_oldEnough_returnsTrue() {
        var policy = createPolicy(Map.of("lastBlobUpdated", 30));
        var asset = createAsset("com/example/lib/1.0/lib-1.0.jar");
        asset.setLastModified(Instant.now().minus(60, ChronoUnit.DAYS));

        assertTrue(evaluator.shouldDelete(policy, asset));
    }

    @Test
    void shouldDelete_lastBlobUpdated_tooRecent_returnsFalse() {
        var policy = createPolicy(Map.of("lastBlobUpdated", 30));
        var asset = createAsset("com/example/lib/1.0/lib-1.0.jar");
        asset.setLastModified(Instant.now().minus(10, ChronoUnit.DAYS));

        assertFalse(evaluator.shouldDelete(policy, asset));
    }

    @Test
    void shouldDelete_lastDownloaded_neverDownloaded_usesCreatedAt() {
        var policy = createPolicy(Map.of("lastDownloaded", 30));
        var asset = createAsset("com/example/lib/1.0/lib-1.0.jar");
        asset.setLastDownloaded(null);
        asset.setCreatedAt(Instant.now().minus(60, ChronoUnit.DAYS));

        assertTrue(evaluator.shouldDelete(policy, asset));
    }

    @Test
    void shouldDelete_lastDownloaded_recentlyDownloaded_returnsFalse() {
        var policy = createPolicy(Map.of("lastDownloaded", 30));
        var asset = createAsset("com/example/lib/1.0/lib-1.0.jar");
        asset.setLastDownloaded(Instant.now().minus(5, ChronoUnit.DAYS));

        assertFalse(evaluator.shouldDelete(policy, asset));
    }

    @Test
    void shouldDelete_lastDownloaded_oldDownload_returnsTrue() {
        var policy = createPolicy(Map.of("lastDownloaded", 30));
        var asset = createAsset("com/example/lib/1.0/lib-1.0.jar");
        asset.setLastDownloaded(Instant.now().minus(45, ChronoUnit.DAYS));

        assertTrue(evaluator.shouldDelete(policy, asset));
    }

    @Test
    void shouldDelete_releaseType_prereleases_matchesSnapshot() {
        var policy = createPolicy(Map.of("releaseType", "PRERELEASES"));
        var asset = createAsset("com/example/lib/1.0-SNAPSHOT/lib-1.0-SNAPSHOT.jar");
        asset.setLastModified(Instant.now());

        assertTrue(evaluator.shouldDelete(policy, asset));
    }

    @Test
    void shouldDelete_releaseType_prereleases_doesNotMatchRelease() {
        var policy = createPolicy(Map.of("releaseType", "PRERELEASES"));
        var asset = createAsset("com/example/lib/1.0/lib-1.0.jar");
        asset.setLastModified(Instant.now());

        assertFalse(evaluator.shouldDelete(policy, asset));
    }

    @Test
    void shouldDelete_releaseType_releases_matchesRelease() {
        var policy = createPolicy(Map.of("releaseType", "RELEASES"));
        var asset = createAsset("com/example/lib/1.0/lib-1.0.jar");
        asset.setLastModified(Instant.now());

        assertTrue(evaluator.shouldDelete(policy, asset));
    }

    @Test
    void shouldDelete_releaseType_releases_doesNotMatchSnapshot() {
        var policy = createPolicy(Map.of("releaseType", "RELEASES"));
        var asset = createAsset("com/example/lib/1.0-SNAPSHOT/lib-1.0-SNAPSHOT.jar");
        asset.setLastModified(Instant.now());

        assertFalse(evaluator.shouldDelete(policy, asset));
    }

    @Test
    void shouldDelete_regex_matches_returnsTrue() {
        var policy = createPolicy(Map.of("regex", ".*\\.jar"));
        var asset = createAsset("com/example/lib/1.0/lib-1.0.jar");
        asset.setLastModified(Instant.now());

        assertTrue(evaluator.shouldDelete(policy, asset));
    }

    @Test
    void shouldDelete_regex_noMatch_returnsFalse() {
        var policy = createPolicy(Map.of("regex", ".*\\.pom"));
        var asset = createAsset("com/example/lib/1.0/lib-1.0.jar");
        asset.setLastModified(Instant.now());

        assertFalse(evaluator.shouldDelete(policy, asset));
    }

    @Test
    void shouldDelete_multipleCriteria_allMatch() {
        var criteria = new HashMap<String, Object>();
        criteria.put("lastBlobUpdated", 30);
        criteria.put("releaseType", "PRERELEASES");
        criteria.put("regex", ".*-SNAPSHOT.*");
        var policy = createPolicy(criteria);

        var asset = createAsset("com/example/lib/1.0-SNAPSHOT/lib-1.0-SNAPSHOT.jar");
        asset.setLastModified(Instant.now().minus(60, ChronoUnit.DAYS));

        assertTrue(evaluator.shouldDelete(policy, asset));
    }

    @Test
    void shouldDelete_multipleCriteria_oneFails() {
        var criteria = new HashMap<String, Object>();
        criteria.put("lastBlobUpdated", 30);
        criteria.put("releaseType", "RELEASES"); // won't match a SNAPSHOT
        var policy = createPolicy(criteria);

        var asset = createAsset("com/example/lib/1.0-SNAPSHOT/lib-1.0-SNAPSHOT.jar");
        asset.setLastModified(Instant.now().minus(60, ChronoUnit.DAYS));

        assertFalse(evaluator.shouldDelete(policy, asset));
    }

    // --- retainNVersions tests ---

    @Test
    void retainNVersions_keepsNewestVersions_deletesOld() {
        UUID repoId = UUID.randomUUID();

        // Create 5 versions of the same component
        var comp1 = createComponent(repoId, "com.example", "lib", "1.0", Instant.parse("2025-01-01T00:00:00Z"));
        var comp2 = createComponent(repoId, "com.example", "lib", "2.0", Instant.parse("2025-02-01T00:00:00Z"));
        var comp3 = createComponent(repoId, "com.example", "lib", "3.0", Instant.parse("2025-03-01T00:00:00Z"));
        var comp4 = createComponent(repoId, "com.example", "lib", "4.0", Instant.parse("2025-04-01T00:00:00Z"));
        var comp5 = createComponent(repoId, "com.example", "lib", "5.0", Instant.parse("2025-05-01T00:00:00Z"));

        when(componentRepository.findByRepositoryId(eq(repoId), eq(Pageable.unpaged())))
                .thenReturn(new PageImpl<>(List.of(comp1, comp2, comp3, comp4, comp5)));

        // Create matching assets - all old enough to pass the lastBlobUpdated criteria
        var asset1 = createAssetWithComponent("com/example/lib/1.0/lib-1.0.jar", comp1.getId(), repoId);
        var asset2 = createAssetWithComponent("com/example/lib/2.0/lib-2.0.jar", comp2.getId(), repoId);
        var asset3 = createAssetWithComponent("com/example/lib/3.0/lib-3.0.jar", comp3.getId(), repoId);
        var asset4 = createAssetWithComponent("com/example/lib/4.0/lib-4.0.jar", comp4.getId(), repoId);
        var asset5 = createAssetWithComponent("com/example/lib/5.0/lib-5.0.jar", comp5.getId(), repoId);

        var criteria = new HashMap<String, Object>();
        criteria.put("lastBlobUpdated", 30);
        criteria.put("retainNVersions", 3);
        var policy = createPolicy(criteria);

        var result = evaluator.evaluateForDeletion(
                policy, List.of(asset1, asset2, asset3, asset4, asset5), repoId, componentRepository);

        // Should delete versions 1.0 and 2.0 (the two oldest), keep 3.0, 4.0, 5.0
        assertEquals(2, result.size());
        assertTrue(result.stream().anyMatch(a -> a.getPath().contains("1.0")));
        assertTrue(result.stream().anyMatch(a -> a.getPath().contains("2.0")));
        assertFalse(result.stream().anyMatch(a -> a.getPath().contains("3.0")));
        assertFalse(result.stream().anyMatch(a -> a.getPath().contains("4.0")));
        assertFalse(result.stream().anyMatch(a -> a.getPath().contains("5.0")));
    }

    @Test
    void retainNVersions_noExcessVersions_deletesNothing() {
        UUID repoId = UUID.randomUUID();

        var comp1 = createComponent(repoId, "com.example", "lib", "1.0", Instant.parse("2025-01-01T00:00:00Z"));
        var comp2 = createComponent(repoId, "com.example", "lib", "2.0", Instant.parse("2025-02-01T00:00:00Z"));

        when(componentRepository.findByRepositoryId(eq(repoId), eq(Pageable.unpaged())))
                .thenReturn(new PageImpl<>(List.of(comp1, comp2)));

        var asset1 = createAssetWithComponent("com/example/lib/1.0/lib-1.0.jar", comp1.getId(), repoId);
        var asset2 = createAssetWithComponent("com/example/lib/2.0/lib-2.0.jar", comp2.getId(), repoId);

        var criteria = new HashMap<String, Object>();
        criteria.put("lastBlobUpdated", 30);
        criteria.put("retainNVersions", 3);
        var policy = createPolicy(criteria);

        var result = evaluator.evaluateForDeletion(
                policy, List.of(asset1, asset2), repoId, componentRepository);

        assertEquals(0, result.size());
    }

    @Test
    void retainNVersions_assetWithoutComponent_isExcluded() {
        UUID repoId = UUID.randomUUID();

        var comp1 = createComponent(repoId, "com.example", "lib", "1.0", Instant.parse("2025-01-01T00:00:00Z"));

        when(componentRepository.findByRepositoryId(eq(repoId), eq(Pageable.unpaged())))
                .thenReturn(new PageImpl<>(List.of(comp1)));

        // Asset without componentId should not be deleted by retainNVersions
        var asset = createAsset("com/example/lib/metadata.xml");
        asset.setLastModified(Instant.now().minus(60, ChronoUnit.DAYS));
        asset.setRepositoryId(repoId);

        var criteria = new HashMap<String, Object>();
        criteria.put("lastBlobUpdated", 30);
        criteria.put("retainNVersions", 1);
        var policy = createPolicy(criteria);

        var result = evaluator.evaluateForDeletion(policy, List.of(asset), repoId, componentRepository);

        // The asset matches lastBlobUpdated but has no component, so retainNVersions filter excludes it
        assertEquals(0, result.size());
    }

    @Test
    void retainNVersions_multipleComponents_groupedSeparately() {
        UUID repoId = UUID.randomUUID();

        // Two different components, each with 3 versions
        var compA1 = createComponent(repoId, "com.example", "libA", "1.0", Instant.parse("2025-01-01T00:00:00Z"));
        var compA2 = createComponent(repoId, "com.example", "libA", "2.0", Instant.parse("2025-02-01T00:00:00Z"));
        var compA3 = createComponent(repoId, "com.example", "libA", "3.0", Instant.parse("2025-03-01T00:00:00Z"));

        var compB1 = createComponent(repoId, "org.other", "libB", "1.0", Instant.parse("2025-01-01T00:00:00Z"));
        var compB2 = createComponent(repoId, "org.other", "libB", "2.0", Instant.parse("2025-02-01T00:00:00Z"));
        var compB3 = createComponent(repoId, "org.other", "libB", "3.0", Instant.parse("2025-03-01T00:00:00Z"));

        when(componentRepository.findByRepositoryId(eq(repoId), eq(Pageable.unpaged())))
                .thenReturn(new PageImpl<>(List.of(compA1, compA2, compA3, compB1, compB2, compB3)));

        var assetA1 = createAssetWithComponent("com/example/libA/1.0/libA-1.0.jar", compA1.getId(), repoId);
        var assetA2 = createAssetWithComponent("com/example/libA/2.0/libA-2.0.jar", compA2.getId(), repoId);
        var assetA3 = createAssetWithComponent("com/example/libA/3.0/libA-3.0.jar", compA3.getId(), repoId);
        var assetB1 = createAssetWithComponent("org/other/libB/1.0/libB-1.0.jar", compB1.getId(), repoId);
        var assetB2 = createAssetWithComponent("org/other/libB/2.0/libB-2.0.jar", compB2.getId(), repoId);
        var assetB3 = createAssetWithComponent("org/other/libB/3.0/libB-3.0.jar", compB3.getId(), repoId);

        var criteria = new HashMap<String, Object>();
        criteria.put("lastBlobUpdated", 30);
        criteria.put("retainNVersions", 2);
        var policy = createPolicy(criteria);

        var result = evaluator.evaluateForDeletion(
                policy, List.of(assetA1, assetA2, assetA3, assetB1, assetB2, assetB3), repoId, componentRepository);

        // Should delete 1 oldest version from each component: libA-1.0 and libB-1.0
        assertEquals(2, result.size());
        assertTrue(result.stream().anyMatch(a -> a.getPath().contains("libA/1.0")));
        assertTrue(result.stream().anyMatch(a -> a.getPath().contains("libB/1.0")));
    }

    @Test
    void evaluateForDeletion_withoutRetainNVersions_usesStandardCriteria() {
        UUID repoId = UUID.randomUUID();
        var policy = createPolicy(Map.of("lastBlobUpdated", 30));

        var asset1 = createAsset("com/example/old.jar");
        asset1.setLastModified(Instant.now().minus(60, ChronoUnit.DAYS));
        asset1.setRepositoryId(repoId);

        var asset2 = createAsset("com/example/new.jar");
        asset2.setLastModified(Instant.now().minus(5, ChronoUnit.DAYS));
        asset2.setRepositoryId(repoId);

        var result = evaluator.evaluateForDeletion(policy, List.of(asset1, asset2), repoId, componentRepository);

        assertEquals(1, result.size());
        assertEquals("com/example/old.jar", result.getFirst().getPath());
    }

    // --- helpers ---

    private static CleanupPolicyEntity createPolicy(Map<String, Object> criteria) {
        var entity = new CleanupPolicyEntity();
        entity.setName("test-policy");
        entity.setCriteria(criteria);
        entity.setCreatedAt(Instant.now());
        return entity;
    }

    private static AssetEntity createAsset(String path) {
        var asset = new AssetEntity();
        asset.setId(UUID.randomUUID());
        asset.setPath(path);
        asset.setFormat("maven2");
        asset.setSize(1024L);
        asset.setLastModified(Instant.now().minus(60, ChronoUnit.DAYS));
        asset.setCreatedAt(Instant.now().minus(60, ChronoUnit.DAYS));
        asset.setUpdatedAt(Instant.now().minus(60, ChronoUnit.DAYS));
        return asset;
    }

    private static AssetEntity createAssetWithComponent(String path, UUID componentId, UUID repoId) {
        var asset = createAsset(path);
        asset.setComponentId(componentId);
        asset.setRepositoryId(repoId);
        return asset;
    }

    private static ComponentEntity createComponent(
            UUID repoId, String namespace, String name, String version, Instant createdAt) {
        var comp = new ComponentEntity();
        comp.setId(UUID.randomUUID());
        comp.setRepositoryId(repoId);
        comp.setFormat("maven2");
        comp.setNamespace(namespace);
        comp.setName(name);
        comp.setVersion(version);
        comp.setCreatedAt(createdAt);
        comp.setUpdatedAt(createdAt);
        return comp;
    }
}
