package de.bsnsoft.megarepo.rest.dto.security;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record NvdBlockXO(
        Long id,
        Instant timestamp,
        String userId,
        String repository,
        String path,
        String componentKey,
        double maxCvssScore,
        List<Map<String, Object>> cveDetails) {}
