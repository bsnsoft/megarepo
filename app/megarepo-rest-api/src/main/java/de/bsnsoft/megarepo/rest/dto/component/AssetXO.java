package de.bsnsoft.megarepo.rest.dto.component;

import java.time.Instant;
import java.util.UUID;

public record AssetXO(
        UUID id,
        String downloadUrl,
        String path,
        String repository,
        String format,
        String checksumMd5,
        String checksumSha1,
        String checksumSha256,
        String checksumSha512,
        String contentType,
        Instant lastModified,
        Instant lastDownloaded,
        long fileSize) {}
