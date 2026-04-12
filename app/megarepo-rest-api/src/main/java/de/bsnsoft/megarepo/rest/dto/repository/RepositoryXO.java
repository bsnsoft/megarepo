package de.bsnsoft.megarepo.rest.dto.repository;

import java.util.Map;

public record RepositoryXO(
        String name,
        String format,
        String type,
        String url,
        boolean online,
        Map<String, Object> attributes,
        long componentCount,
        long assetCount,
        long totalSize) {}
