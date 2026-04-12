package de.bsnsoft.megarepo.tasks;

import de.bsnsoft.megarepo.database.entity.AssetEntity;
import de.bsnsoft.megarepo.database.entity.CleanupPolicyEntity;
import de.bsnsoft.megarepo.tasks.cleanup.CleanupPolicyEvaluator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CleanupPolicyEvaluatorTest {

    private CleanupPolicyEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new CleanupPolicyEvaluator();
    }

    @Test
    void assetOlderThanLastBlobUpdatedDays_shouldDelete() {
        var policy = createPolicy(Map.of("lastBlobUpdated", 30));
        var asset = createAsset("/com/example/artifact-1.0.jar");
        asset.setLastModified(Instant.now().minus(60, ChronoUnit.DAYS));

        assertTrue(evaluator.shouldDelete(policy, asset));
    }

    @Test
    void assetNewerThanLastBlobUpdatedDays_shouldNotDelete() {
        var policy = createPolicy(Map.of("lastBlobUpdated", 30));
        var asset = createAsset("/com/example/artifact-1.0.jar");
        asset.setLastModified(Instant.now().minus(10, ChronoUnit.DAYS));

        assertFalse(evaluator.shouldDelete(policy, asset));
    }

    @Test
    void assetNeverDownloadedWithLastDownloadedCriteria_shouldDelete() {
        var policy = createPolicy(Map.of("lastDownloaded", 14));
        var asset = createAsset("/com/example/artifact-1.0.jar");
        asset.setLastDownloaded(null);
        asset.setCreatedAt(Instant.now().minus(30, ChronoUnit.DAYS));

        assertTrue(evaluator.shouldDelete(policy, asset));
    }

    @Test
    void assetRecentlyDownloaded_shouldNotDelete() {
        var policy = createPolicy(Map.of("lastDownloaded", 14));
        var asset = createAsset("/com/example/artifact-1.0.jar");
        asset.setLastDownloaded(Instant.now().minus(3, ChronoUnit.DAYS));

        assertFalse(evaluator.shouldDelete(policy, asset));
    }

    @Test
    void regexMatch_shouldDelete() {
        var policy = createPolicy(Map.of("regex", ".*-SNAPSHOT\\.jar"));
        var asset = createAsset("/com/example/artifact-1.0-SNAPSHOT.jar");
        asset.setLastModified(Instant.now());

        assertTrue(evaluator.shouldDelete(policy, asset));
    }

    @Test
    void regexNoMatch_shouldNotDelete() {
        var policy = createPolicy(Map.of("regex", ".*-SNAPSHOT\\.jar"));
        var asset = createAsset("/com/example/artifact-1.0.jar");
        asset.setLastModified(Instant.now());

        assertFalse(evaluator.shouldDelete(policy, asset));
    }

    @Test
    void multipleCriteriaAnded_allMustMatch() {
        var criteria = new HashMap<String, Object>();
        criteria.put("lastBlobUpdated", 30);
        criteria.put("regex", ".*\\.jar");
        var policy = createPolicy(criteria);

        // Old enough + matches regex -> delete
        var asset1 = createAsset("/com/example/artifact-1.0.jar");
        asset1.setLastModified(Instant.now().minus(60, ChronoUnit.DAYS));
        assertTrue(evaluator.shouldDelete(policy, asset1));

        // Old enough + does NOT match regex -> don't delete
        var asset2 = createAsset("/com/example/artifact-1.0.pom");
        asset2.setLastModified(Instant.now().minus(60, ChronoUnit.DAYS));
        assertFalse(evaluator.shouldDelete(policy, asset2));

        // Matches regex + NOT old enough -> don't delete
        var asset3 = createAsset("/com/example/artifact-1.0.jar");
        asset3.setLastModified(Instant.now().minus(5, ChronoUnit.DAYS));
        assertFalse(evaluator.shouldDelete(policy, asset3));
    }

    @Test
    void noCriteria_shouldNotDelete() {
        var policy = createPolicy(Map.of());
        var asset = createAsset("/com/example/artifact-1.0.jar");
        asset.setLastModified(Instant.now().minus(365, ChronoUnit.DAYS));

        assertFalse(evaluator.shouldDelete(policy, asset));
    }

    @Test
    void releaseTypePrereleasesFilteringSnapshot_shouldDelete() {
        var policy = createPolicy(Map.of("releaseType", "PRERELEASES"));
        var asset = createAsset("/com/example/artifact-1.0-SNAPSHOT.jar");
        asset.setLastModified(Instant.now());

        assertTrue(evaluator.shouldDelete(policy, asset));
    }

    @Test
    void releaseTypePrereleasesFilteringRelease_shouldNotDelete() {
        var policy = createPolicy(Map.of("releaseType", "PRERELEASES"));
        var asset = createAsset("/com/example/artifact-1.0.jar");
        asset.setLastModified(Instant.now());

        assertFalse(evaluator.shouldDelete(policy, asset));
    }

    @Test
    void releaseTypeReleasesFilteringRelease_shouldDelete() {
        var policy = createPolicy(Map.of("releaseType", "RELEASES"));
        var asset = createAsset("/com/example/artifact-1.0.jar");
        asset.setLastModified(Instant.now());

        assertTrue(evaluator.shouldDelete(policy, asset));
    }

    @Test
    void releaseTypeReleasesFilteringSnapshot_shouldNotDelete() {
        var policy = createPolicy(Map.of("releaseType", "RELEASES"));
        var asset = createAsset("/com/example/artifact-1.0-SNAPSHOT.jar");
        asset.setLastModified(Instant.now());

        assertFalse(evaluator.shouldDelete(policy, asset));
    }

    private CleanupPolicyEntity createPolicy(Map<String, Object> criteria) {
        var policy = new CleanupPolicyEntity();
        policy.setName("test-policy");
        policy.setCriteria(new HashMap<>(criteria));
        policy.setCreatedAt(Instant.now());
        return policy;
    }

    private AssetEntity createAsset(String path) {
        var asset = new AssetEntity();
        asset.setPath(path);
        asset.setLastModified(Instant.now());
        asset.setCreatedAt(Instant.now());
        asset.setUpdatedAt(Instant.now());
        return asset;
    }
}
