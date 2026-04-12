package de.bsnsoft.megarepo.core.storage;

import java.time.Instant;
import java.util.Map;

public record BlobProperties(
        long size,
        String contentType,
        Map<String, String> checksums,
        Instant createdAt,
        Map<String, String> headers
) {
}
