package de.bsnsoft.megarepo.rest.dto.task;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record TaskXO(
        UUID id,
        String name,
        String type,
        String cronExpression,
        Map<String, Object> config,
        boolean enabled,
        String currentState,
        Instant lastRun,
        String lastRunResult,
        Instant nextRun,
        String message) {}
