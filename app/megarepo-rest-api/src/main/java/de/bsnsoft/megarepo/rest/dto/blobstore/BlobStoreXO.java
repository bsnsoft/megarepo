package de.bsnsoft.megarepo.rest.dto.blobstore;

import java.util.Map;

public record BlobStoreXO(
        String name,
        String type,
        long blobCount,
        long totalSizeInBytes,
        Long availableSpaceInBytes,
        Map<String, Object> config) {}
