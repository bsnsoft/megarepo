package de.bsnsoft.megarepo.app.migration;

import java.util.List;

public record NexusMigrationResult(
        int created,
        int skippedExisting,
        int skippedUnsupported,
        List<String> errors) {}
