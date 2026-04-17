package de.bsnsoft.megarepo.rest.dto.security;

import java.time.Instant;

public record NvdSyncStateXO(
        String status,
        String mode,
        Instant startedAt,
        Instant lastSyncAt,
        Instant lastSuccessAt,
        int totalCves,
        int syncedCves,
        Integer totalResults,
        String errorMessage) {}
