package de.bsnsoft.megarepo.repository;

import java.time.Instant;

public record ActivityEvent(
        Instant timestamp,
        String user,
        String action,
        String repository,
        String path,
        String format,
        Long size,
        long durationMs,
        String sourceUrl) {}
