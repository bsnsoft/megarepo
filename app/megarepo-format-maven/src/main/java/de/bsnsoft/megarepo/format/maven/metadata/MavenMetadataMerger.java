package de.bsnsoft.megarepo.format.maven.metadata;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

@Component
public class MavenMetadataMerger {

    /**
     * Merges multiple maven-metadata.xml models into a single unified model.
     * Versions are deduplicated, sorted, and the latest/release fields are recalculated.
     */
    public MavenMetadataModel mergeMetadata(List<MavenMetadataModel> sources) {
        if (sources == null || sources.isEmpty()) {
            throw new IllegalArgumentException("Cannot merge empty metadata list");
        }

        if (sources.size() == 1) {
            return sources.getFirst();
        }

        String groupId = sources.getFirst().groupId();
        String artifactId = sources.getFirst().artifactId();

        // Collect all versions from all sources, preserving insertion order for dedup
        var allVersions = new LinkedHashSet<String>();
        String mostRecentLastUpdated = null;

        for (var source : sources) {
            if (source.versioning() != null && source.versioning().versions() != null) {
                allVersions.addAll(source.versioning().versions());
            }
            if (source.versioning() != null && source.versioning().lastUpdated() != null) {
                if (mostRecentLastUpdated == null
                        || source.versioning().lastUpdated().compareTo(mostRecentLastUpdated) > 0) {
                    mostRecentLastUpdated = source.versioning().lastUpdated();
                }
            }
        }

        List<String> sortedVersions = new ArrayList<>(allVersions);
        sortedVersions.sort(VERSION_COMPARATOR);

        String latest = sortedVersions.isEmpty() ? null : sortedVersions.getLast();
        String release = sortedVersions.stream()
                .filter(v -> !v.endsWith("-SNAPSHOT"))
                .reduce((first, second) -> second)
                .orElse(null);

        return new MavenMetadataModel(
                groupId,
                artifactId,
                null,
                new MavenMetadataModel.Versioning(
                        latest, release, sortedVersions, mostRecentLastUpdated, null, null));
    }

    private static final Comparator<String> VERSION_COMPARATOR = (v1, v2) -> {
        String[] parts1 = v1.split("[.\\-]");
        String[] parts2 = v2.split("[.\\-]");
        int len = Math.max(parts1.length, parts2.length);

        for (int i = 0; i < len; i++) {
            String p1 = i < parts1.length ? parts1[i] : "0";
            String p2 = i < parts2.length ? parts2[i] : "0";

            try {
                int n1 = Integer.parseInt(p1);
                int n2 = Integer.parseInt(p2);
                int cmp = Integer.compare(n1, n2);
                if (cmp != 0) {
                    return cmp;
                }
            } catch (NumberFormatException e) {
                int cmp = p1.compareTo(p2);
                if (cmp != 0) {
                    return cmp;
                }
            }
        }
        return 0;
    };
}
