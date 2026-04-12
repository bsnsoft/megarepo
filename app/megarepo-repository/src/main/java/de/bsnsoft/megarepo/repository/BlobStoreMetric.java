package de.bsnsoft.megarepo.repository;

public record BlobStoreMetric(
        String name, String type, long blobCount, long totalSizeBytes, Long availableSpaceBytes) {}
