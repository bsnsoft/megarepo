package de.bsnsoft.megarepo.repository.firewall.rule.corpus;

import de.bsnsoft.megarepo.core.repository.RepositoryType;
import de.bsnsoft.megarepo.database.entity.ComponentEntity;
import de.bsnsoft.megarepo.database.entity.RepositoryEntity;
import de.bsnsoft.megarepo.database.repository.ComponentJpaRepository;
import de.bsnsoft.megarepo.database.repository.RepositoryJpaRepository;
import de.bsnsoft.megarepo.repository.firewall.identity.PurlBuilder;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Keeps the {@link ComponentNameCorpus} warm and hands it to the rules.
 *
 * <h2>The request path never waits</h2>
 *
 * {@link #corpus()} returns whatever snapshot is currently published and, if it
 * has gone stale, <em>starts</em> a refresh on a background thread rather than
 * performing one. This is the difference between a cache and a query: the corpus
 * is a full scan of the {@code components} table, which is several orders of
 * magnitude outside the 20 ms budget the whole firewall evaluation has, and the
 * design's third standing rule ("no network and no blocking I/O on a request
 * thread") does not have an exception for "only every half hour".
 *
 * <p>The cost of that choice is that a rule may read a corpus up to one refresh
 * interval old. For a set of package names, stale means "a package proxied in
 * the last half hour is not in it yet" — which affects a heuristic's ability to
 * spot a resemblance to a very recently introduced dependency, and nothing else.
 *
 * <h2>Never loaded is not the same as empty</h2>
 *
 * Before the first scan completes the corpus is
 * {@link ComponentNameCorpus#neverLoaded()}, and the two rules read that state
 * differently on purpose: for {@code TYPOSQUAT} a missing corpus can only cost
 * a warning, for {@code NAMESPACE_CONFUSION} deriving internal namespaces from
 * hosted repositories it would silently disable a rule that exists to stop
 * dependency confusion. See the two rule classes.
 *
 * <h2>Failure</h2>
 *
 * A scan that throws leaves the previous snapshot in place and is retried after
 * the interval. A firewall component that cannot read the database must not take
 * downloads down with it (standing rule 4).
 */
@Service
public class ComponentCorpusService {

    private static final Logger log = LoggerFactory.getLogger(ComponentCorpusService.class);

    private final ComponentJpaRepository components;
    private final RepositoryJpaRepository repositories;
    private final PurlBuilder purlBuilder;
    private final ComponentCorpusProperties properties;

    private final AtomicReference<ComponentNameCorpus> snapshot =
            new AtomicReference<>(ComponentNameCorpus.notLoadedYet());
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private final AtomicReference<Instant> lastAttempt = new AtomicReference<>();
    private final ExecutorService executor;

    public ComponentCorpusService(
            ComponentJpaRepository components,
            RepositoryJpaRepository repositories,
            PurlBuilder purlBuilder,
            ComponentCorpusProperties properties) {
        this.components = components;
        this.repositories = repositories;
        this.purlBuilder = purlBuilder;
        this.properties = properties;
        // One daemon thread: the scan is not urgent, two of them would only
        // contend for the same table, and a daemon thread cannot hold up a
        // shutdown that happens mid-scan.
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "firewall-name-corpus");
            thread.setDaemon(true);
            return thread;
        });
        if (!properties.enabled()) {
            // A switched-off corpus publishes an empty but *settled* snapshot
            // rather than staying "never loaded". The difference matters:
            // "never loaded" means "ask again in a moment", and
            // NAMESPACE_CONFUSION turns that into an undecidable evaluation.
            // An operator who switched the scan off has decided, and the rules
            // must read a decision, not a pending state.
            this.snapshot.set(ComponentNameCorpus.builder().build(Instant.now()));
            this.lastAttempt.set(Instant.now());
        }
    }

    /**
     * The current corpus. Reads memory, never the database.
     *
     * <p>Triggers a background refresh when the published snapshot is older than
     * {@link ComponentCorpusProperties#refreshInterval()}, and returns the old
     * one regardless.
     */
    public ComponentNameCorpus corpus() {
        if (!properties.enabled()) {
            return snapshot.get();
        }
        Instant attempted = lastAttempt.get();
        if (attempted == null
                || attempted.plus(properties.refreshInterval()).isBefore(Instant.now())) {
            requestRefresh();
        }
        return snapshot.get();
    }

    /**
     * Builds the first corpus once the context is up.
     *
     * <p>Asynchronously: this runs on the thread that finished the start-up, the
     * scan can take a while on a large instance, and an instance that is slow to
     * accept its first request because a heuristic's cache is filling would be a
     * poor trade. Downloads served in the meantime see
     * {@link ComponentNameCorpus#neverLoaded()}.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        if (properties.enabled()) {
            requestRefresh();
        } else {
            log.info("Firewall name corpus is disabled; TYPOSQUAT will not match and "
                    + "NAMESPACE_CONFUSION uses its configured namespaces only");
        }
    }

    /** Starts a refresh unless one is already running. Returns immediately. */
    public void requestRefresh() {
        if (!refreshing.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.execute(this::runRefresh);
        } catch (RuntimeException e) {
            // Rejected because the executor is shutting down.
            refreshing.set(false);
            log.debug("Firewall name corpus refresh not scheduled: {}", e.getMessage());
        }
    }

    /**
     * Scans the components table and publishes a new snapshot. Never throws.
     *
     * <p>Package-private and synchronous so a test can build a corpus without
     * waiting on a thread; production callers go through {@link #requestRefresh()}.
     */
    void runRefresh() {
        Instant startedAt = Instant.now();
        try {
            ComponentNameCorpus loaded = scan();
            snapshot.set(loaded);
            log.info("Firewall name corpus refreshed: {} names from {} components in {} ms{}",
                    loaded.size(),
                    loaded.scannedComponents(),
                    Duration.between(startedAt, Instant.now()).toMillis(),
                    loaded.truncated() ? " (scan capped, corpus is partial)" : "");
        } catch (RuntimeException e) {
            log.warn("Could not refresh the firewall name corpus; keeping the previous one "
                    + "({} names): {}", snapshot.get().size(), e.getMessage());
        } finally {
            lastAttempt.set(Instant.now());
            refreshing.set(false);
        }
    }

    private ComponentNameCorpus scan() {
        Map<UUID, RepositoryEntity> byId = new HashMap<>();
        for (RepositoryEntity repository : repositories.findAll()) {
            byId.put(repository.getId(), repository);
        }

        ComponentNameCorpus.Builder builder = ComponentNameCorpus.builder();
        int pageIndex = 0;
        long rowsRead = 0;
        boolean truncated = false;
        while (true) {
            Pageable page = PageRequest.of(
                    pageIndex, properties.batchSize(), Sort.by(Sort.Direction.ASC, "id"));
            List<ComponentEntity> batch = components.findAllByIdNotNull(page);
            if (batch.isEmpty()) {
                break;
            }
            for (ComponentEntity component : batch) {
                RepositoryEntity repository = byId.get(component.getRepositoryId());
                boolean hosted = repository != null
                        && RepositoryType.HOSTED.name().equalsIgnoreCase(repository.getType());
                String repositoryName = repository == null ? null : repository.getName();
                purlBuilder.toPurl(component)
                        .ifPresent(purl -> builder.add(purl, hosted, repositoryName));
            }
            rowsRead += batch.size();
            if (batch.size() < properties.batchSize()) {
                break;
            }
            if (rowsRead >= properties.maxComponents()) {
                truncated = true;
                log.warn("Firewall name corpus scan stopped at {} components "
                                + "(megarepo.firewall.corpus.max-components); the corpus is partial "
                                + "and the typosquat heuristic may miss resemblances",
                        rowsRead);
                break;
            }
            pageIndex++;
        }
        return builder.scanned(rowsRead).truncated(truncated).build(Instant.now());
    }

    /** Replaces the published snapshot. For tests and for a forced rebuild. */
    public void publish(ComponentNameCorpus corpus) {
        snapshot.set(corpus == null ? ComponentNameCorpus.notLoadedYet() : corpus);
        lastAttempt.set(Instant.now());
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
