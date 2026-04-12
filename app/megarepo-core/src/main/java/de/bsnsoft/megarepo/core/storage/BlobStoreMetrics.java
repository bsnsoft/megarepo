package de.bsnsoft.megarepo.core.storage;

public record BlobStoreMetrics(long blobCount, long totalSizeBytes, Long availableSpaceBytes) {
}
