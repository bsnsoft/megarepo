package de.bsnsoft.megarepo.repository;

import de.bsnsoft.megarepo.database.repository.AuditLogJpaRepository;
import de.bsnsoft.megarepo.database.repository.AssetJpaRepository;
import de.bsnsoft.megarepo.database.repository.ComponentJpaRepository;
import de.bsnsoft.megarepo.database.repository.RepositoryJpaRepository;
import de.bsnsoft.megarepo.storage.BlobStoreManager;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
public class MetricsService {

    private final BlobStoreManager blobStoreManager;
    private final RepositoryJpaRepository repositoryJpaRepository;
    private final ComponentJpaRepository componentJpaRepository;
    private final AssetJpaRepository assetJpaRepository;
    private final AuditLogJpaRepository auditLogJpaRepository;

    public MetricsService(
            BlobStoreManager blobStoreManager,
            RepositoryJpaRepository repositoryJpaRepository,
            ComponentJpaRepository componentJpaRepository,
            AssetJpaRepository assetJpaRepository,
            AuditLogJpaRepository auditLogJpaRepository) {
        this.blobStoreManager = blobStoreManager;
        this.repositoryJpaRepository = repositoryJpaRepository;
        this.componentJpaRepository = componentJpaRepository;
        this.assetJpaRepository = assetJpaRepository;
        this.auditLogJpaRepository = auditLogJpaRepository;
    }

    public SystemMetrics getSystemMetrics() {
        var blobStores = blobStoreManager.list().stream()
                .map(info -> new BlobStoreMetric(
                        info.name(),
                        info.type().name(),
                        info.metrics().blobCount(),
                        info.metrics().totalSizeBytes(),
                        info.metrics().availableSpaceBytes()))
                .toList();

        var repos = repositoryJpaRepository.findAll();
        var repositoryMetrics = repos.stream()
                .map(repo -> new RepositoryMetric(
                        repo.getName(),
                        repo.getFormat(),
                        repo.getType(),
                        componentJpaRepository.countByRepositoryId(repo.getId()),
                        assetJpaRepository.countByRepositoryId(repo.getId())))
                .toList();

        long totalComponents = repositoryMetrics.stream().mapToLong(RepositoryMetric::componentCount).sum();
        long totalAssets = repositoryMetrics.stream().mapToLong(RepositoryMetric::assetCount).sum();
        long totalBlobSize = blobStores.stream().mapToLong(BlobStoreMetric::totalSizeBytes).sum();

        var totals = new TotalMetrics(repos.size(), totalComponents, totalAssets, totalBlobSize);

        return new SystemMetrics(blobStores, repositoryMetrics, totals);
    }

    public ThroughputMetrics getThroughputMetrics(Duration window) {
        Instant since = Instant.now().minus(window);
        double minutes = window.toSeconds() / 60.0;

        long downloads = auditLogJpaRepository.countByActionAndTimestampAfter("DOWNLOAD", since);
        long uploads = auditLogJpaRepository.countByActionAndTimestampAfter("UPLOAD", since);
        long proxyFetches = auditLogJpaRepository.countByActionAndTimestampAfter("PROXY_FETCH", since);
        long cacheHits = auditLogJpaRepository.countByActionAndTimestampAfter("CACHE_HIT", since);

        long bytesDownloaded = auditLogJpaRepository.sumSizeByActionAndTimestampAfter("DOWNLOAD", since);
        long bytesUploaded = auditLogJpaRepository.sumSizeByActionAndTimestampAfter("UPLOAD", since);

        double cacheHitRatio = 0.0;
        long totalCacheRequests = cacheHits + proxyFetches;
        if (totalCacheRequests > 0) {
            cacheHitRatio = (double) cacheHits / totalCacheRequests;
        }

        return new ThroughputMetrics(
                downloads / minutes,
                uploads / minutes,
                proxyFetches / minutes,
                bytesDownloaded,
                bytesUploaded,
                cacheHitRatio);
    }
}
