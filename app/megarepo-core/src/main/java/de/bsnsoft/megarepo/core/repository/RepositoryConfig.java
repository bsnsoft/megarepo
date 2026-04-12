package de.bsnsoft.megarepo.core.repository;

import java.util.Map;
import java.util.UUID;

public record RepositoryConfig(
        UUID id,
        String name,
        String format,
        RepositoryType type,
        boolean online,
        String blobStoreName,
        Map<String, Object> attributes
) {
}
