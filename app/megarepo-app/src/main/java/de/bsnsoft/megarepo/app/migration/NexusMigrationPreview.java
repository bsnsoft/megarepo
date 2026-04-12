package de.bsnsoft.megarepo.app.migration;

import java.util.List;

public record NexusMigrationPreview(
        List<RepoPreview> importable,
        List<SkippedRepo> skipped) {

    public record RepoPreview(
            String name,
            String format,
            String type,
            String remoteUrl,
            List<String> groupMembers,
            boolean alreadyExists) {}

    public record SkippedRepo(
            String name,
            String format,
            String type,
            String reason) {}
}
