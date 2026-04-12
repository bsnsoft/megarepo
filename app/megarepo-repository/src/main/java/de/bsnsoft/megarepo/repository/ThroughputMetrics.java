package de.bsnsoft.megarepo.repository;

public record ThroughputMetrics(
        double downloadsPerMinute,
        double uploadsPerMinute,
        double proxyFetchesPerMinute,
        long bytesDownloaded,
        long bytesUploaded,
        double cacheHitRatio) {}
