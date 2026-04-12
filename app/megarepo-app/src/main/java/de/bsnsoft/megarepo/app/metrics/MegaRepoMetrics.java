package de.bsnsoft.megarepo.app.metrics;

import de.bsnsoft.megarepo.database.repository.AssetJpaRepository;
import de.bsnsoft.megarepo.database.repository.ComponentJpaRepository;
import de.bsnsoft.megarepo.database.repository.RepositoryJpaRepository;
import de.bsnsoft.megarepo.database.repository.UserJpaRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Registers custom MegaRepo metrics with Micrometer for Prometheus scraping.
 *
 * <p>Gauges are polled lazily by the registry and always reflect current DB state.
 * Counters must be incremented by callers via the accessor methods.</p>
 */
@Component
public class MegaRepoMetrics {

    private final Counter artifactDownloads;
    private final Counter artifactUploads;
    private final Counter proxyCacheHits;
    private final Counter proxyCacheMisses;

    public MegaRepoMetrics(
            MeterRegistry registry,
            RepositoryJpaRepository repositoryJpaRepository,
            ComponentJpaRepository componentJpaRepository,
            AssetJpaRepository assetJpaRepository,
            UserJpaRepository userJpaRepository) {

        // Gauges — current state from the database
        registry.gauge("megarepo.repositories.count", repositoryJpaRepository, repo -> repo.count());

        registry.gauge("megarepo.components.count", componentJpaRepository, repo -> repo.count());

        registry.gauge(
                "megarepo.storage.bytes.total", assetJpaRepository, repo -> repo.sumTotalSize());

        registry.gauge(
                "megarepo.users.active.count",
                userJpaRepository,
                repo -> repo.countByStatus("ACTIVE"));

        // Counters — incremented at runtime by other services
        artifactDownloads = Counter.builder("megarepo.artifacts.downloads")
                .description("Total number of artifact downloads")
                .register(registry);

        artifactUploads = Counter.builder("megarepo.artifacts.uploads")
                .description("Total number of artifact uploads")
                .register(registry);

        proxyCacheHits = Counter.builder("megarepo.proxy.cache.hits")
                .description("Number of proxy cache hits")
                .register(registry);

        proxyCacheMisses = Counter.builder("megarepo.proxy.cache.misses")
                .description("Number of proxy cache misses (remote fetches)")
                .register(registry);
    }

    public void recordDownload() {
        artifactDownloads.increment();
    }

    public void recordUpload() {
        artifactUploads.increment();
    }

    public void recordCacheHit() {
        proxyCacheHits.increment();
    }

    public void recordCacheMiss() {
        proxyCacheMisses.increment();
    }
}
