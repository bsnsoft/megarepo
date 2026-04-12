package de.bsnsoft.megarepo.rest.dto.audit;

import java.time.Instant;

public record AuditLogXO(
        Long id,
        Instant timestamp,
        String userId,
        String action,
        String repository,
        String path,
        String sourceUrl,
        Long size,
        String ipAddress,
        String format,
        Long durationMs) {}
