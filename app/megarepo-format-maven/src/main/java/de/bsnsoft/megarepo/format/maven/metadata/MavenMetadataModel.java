package de.bsnsoft.megarepo.format.maven.metadata;

import java.util.List;

public record MavenMetadataModel(
        String groupId,
        String artifactId,
        String version,
        Versioning versioning) {

    public record Versioning(
            String latest,
            String release,
            List<String> versions,
            String lastUpdated,
            SnapshotInfo snapshot,
            List<SnapshotVersion> snapshotVersions) {}

    public record SnapshotInfo(String timestamp, int buildNumber) {}

    public record SnapshotVersion(String classifier, String extension, String value, String updated) {}
}
