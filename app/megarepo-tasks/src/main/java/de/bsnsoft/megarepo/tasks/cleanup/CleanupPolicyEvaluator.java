package de.bsnsoft.megarepo.tasks.cleanup;

import de.bsnsoft.megarepo.database.entity.AssetEntity;
import de.bsnsoft.megarepo.database.entity.CleanupPolicyEntity;
import de.bsnsoft.megarepo.database.entity.ComponentEntity;
import de.bsnsoft.megarepo.database.repository.ComponentJpaRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
public class CleanupPolicyEvaluator {

    /**
     * Evaluates whether an asset should be deleted based on the given cleanup policy.
     * All criteria in the policy are ANDed together: the asset is only marked for
     * deletion when every specified criterion matches.
     *
     * <p>Note: the {@code retainNVersions} criterion cannot be evaluated per-asset;
     * use {@link #evaluateForDeletion} for batch evaluation that includes version retention.
     *
     * @return true if the asset should be deleted
     */
    public boolean shouldDelete(CleanupPolicyEntity policy, AssetEntity asset) {
        Map<String, Object> criteria = policy.getCriteria();
        if (criteria == null || criteria.isEmpty()) {
            return false;
        }

        var now = Instant.now();
        boolean allMatch = true;

        if (criteria.containsKey("lastBlobUpdated")) {
            int days = toInt(criteria.get("lastBlobUpdated"));
            var threshold = now.minus(days, ChronoUnit.DAYS);
            allMatch = asset.getLastModified() != null && asset.getLastModified().isBefore(threshold);
        }

        if (allMatch && criteria.containsKey("lastDownloaded")) {
            int days = toInt(criteria.get("lastDownloaded"));
            var threshold = now.minus(days, ChronoUnit.DAYS);
            if (asset.getLastDownloaded() != null) {
                allMatch = asset.getLastDownloaded().isBefore(threshold);
            } else {
                // Never downloaded: check if created long enough ago
                allMatch = asset.getCreatedAt() != null && asset.getCreatedAt().isBefore(threshold);
            }
        }

        if (allMatch && criteria.containsKey("regex")) {
            var regex = criteria.get("regex").toString();
            allMatch = Pattern.matches(regex, asset.getPath());
        }

        if (allMatch && criteria.containsKey("releaseType")) {
            var releaseType = criteria.get("releaseType").toString();
            allMatch = matchesReleaseType(releaseType, asset);
        }

        return allMatch;
    }

    /**
     * Evaluates a batch of assets against a policy, including the {@code retainNVersions}
     * criterion that requires cross-asset version comparison.
     *
     * <p>Returns only the assets that should be deleted.
     */
    public List<AssetEntity> evaluateForDeletion(
            CleanupPolicyEntity policy,
            List<AssetEntity> assets,
            UUID repositoryId,
            ComponentJpaRepository componentRepository) {

        Map<String, Object> criteria = policy.getCriteria();
        if (criteria == null || criteria.isEmpty()) {
            return List.of();
        }

        // First pass: filter by per-asset criteria (everything except retainNVersions)
        List<AssetEntity> candidates = new ArrayList<>();
        for (var asset : assets) {
            if (shouldDelete(policy, asset)) {
                candidates.add(asset);
            }
        }

        // If retainNVersions is not specified, we're done
        if (!criteria.containsKey("retainNVersions")) {
            return candidates;
        }

        int retainN = toInt(criteria.get("retainNVersions"));
        Set<UUID> versionDeletionSet = computeVersionDeletionSet(repositoryId, retainN, componentRepository);

        // Intersect: keep only candidates whose component version is marked for deletion
        return candidates.stream()
                .filter(asset -> asset.getComponentId() != null && versionDeletionSet.contains(asset.getComponentId()))
                .toList();
    }

    /**
     * Computes the set of component IDs whose versions should be deleted based on
     * the retainNVersions policy. For each unique (namespace, name) group in the
     * repository, sorts versions by creation time (newest first) and marks all
     * versions beyond the Nth for deletion.
     */
    Set<UUID> computeVersionDeletionSet(UUID repositoryId, int retainN, ComponentJpaRepository componentRepository) {
        Set<UUID> deletionSet = new HashSet<>();

        // Group components by (namespace, name)
        var allComponents = componentRepository.findByRepositoryId(
                repositoryId, org.springframework.data.domain.Pageable.unpaged());

        Map<String, List<ComponentEntity>> grouped = new HashMap<>();
        for (var component : allComponents) {
            String key = component.getNamespace() + ":" + component.getName();
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(component);
        }

        for (var entry : grouped.values()) {
            // Sort by createdAt descending (newest first)
            entry.sort(Comparator.comparing(ComponentEntity::getCreatedAt).reversed());

            // Mark versions beyond retainN for deletion
            if (entry.size() > retainN) {
                for (int i = retainN; i < entry.size(); i++) {
                    deletionSet.add(entry.get(i).getId());
                }
            }
        }

        return deletionSet;
    }

    private boolean matchesReleaseType(String releaseType, AssetEntity asset) {
        var path = asset.getPath().toLowerCase();
        boolean isPrerelease = path.contains("-snapshot")
                || path.contains("-alpha")
                || path.contains("-beta")
                || path.contains("-rc");

        return switch (releaseType.toUpperCase()) {
            case "PRERELEASES" -> isPrerelease;
            case "RELEASES" -> !isPrerelease;
            default -> true;
        };
    }

    private int toInt(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        return Integer.parseInt(value.toString());
    }
}
