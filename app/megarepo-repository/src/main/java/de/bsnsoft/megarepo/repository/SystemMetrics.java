package de.bsnsoft.megarepo.repository;

import java.util.List;

public record SystemMetrics(
        List<BlobStoreMetric> blobStores, List<RepositoryMetric> repositories, TotalMetrics totals) {}
