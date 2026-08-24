package de.bsnsoft.megarepo.repository.firewall.facts;

import com.github.packageurl.MalformedPackageURLException;
import com.github.packageurl.PackageURL;
import de.bsnsoft.megarepo.core.firewall.FirewallFactsState;
import de.bsnsoft.megarepo.database.entity.FirewallComponentFactsEntity;
import de.bsnsoft.megarepo.database.repository.FirewallComponentFactsJpaRepository;
import de.bsnsoft.megarepo.repository.firewall.facts.ComponentFactsSource.ResolvedFacts;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The background half of the facts store: everything that may take time, block
 * or leave the machine.
 *
 * <p>Two triggers, one code path. The request path enqueues through
 * {@link ComponentFactsService#requestResolution} and a small pool drains the
 * queue continuously, because a developer whose download was held for "we do not
 * know how old this is" should not wait for a cron tick. The V19 task
 * ({@code security.firewall.facts.resolve}, every 10 minutes) calls
 * {@link #sweep()}, which finds the rows an in-process queue cannot survive a
 * restart with, plus the settled rows old enough to be worth asking about again.
 *
 * <h2>What a failure does</h2>
 *
 * A source that throws marks an attempt and leaves the row unresolved; the other
 * ecosystems are untouched, because one failing registry must not stop the
 * queue. After {@code max-attempts} the row settles as
 * {@link FirewallFactsState#UNAVAILABLE} — a row that is retried forever is a
 * background job that never gets to the rest of its work, and a rule reading
 * {@code UNAVAILABLE} answers "cannot judge" rather than holding the component.
 *
 * <p>{@code UNAVAILABLE} is also the answer for a purl type no source claims.
 * Raw files and Docker layers have no ecosystem to ask, and the alternative —
 * leaving them {@code UNKNOWN} — would keep them permanently indeterminate and,
 * under a fail-closed repository, permanently quarantined.
 */
@Component
public class ComponentFactsResolver {

    private static final Logger log = LoggerFactory.getLogger(ComponentFactsResolver.class);

    /** Unsettled states the sweep treats as work. */
    private static final Set<FirewallFactsState> UNRESOLVED =
            Set.of(FirewallFactsState.UNKNOWN, FirewallFactsState.PENDING);

    /** {@code firewall_component_facts.license_source} has a CHECK constraint for these. */
    private static final Set<String> LICENSE_SOURCES =
            Set.of(ResolvedFacts.PACKAGE_METADATA, ResolvedFacts.UPSTREAM_REGISTRY);

    private static final int MAX_SOURCE_ID = 40;
    private static final int MAX_ERROR_MESSAGE = 1000;

    private final FirewallComponentFactsJpaRepository factsRepository;
    private final ComponentFactsProperties properties;

    /** purl type (lowercased), including aliases, to the source that answers for it. */
    private final Map<String, ComponentFactsSource> sourcesByPurlType;

    /**
     * Coordinates waiting to be resolved, plus the set that makes enqueueing
     * idempotent. {@link ComponentFactsService#requestResolution} is called on
     * every download of an unresolved component — without the set, a popular new
     * package would queue one entry per request and the pool would resolve it a
     * few hundred times.
     */
    private final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>();
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    private volatile ExecutorService drainPool;

    /**
     * Runs the one source call an attempt consists of, so that
     * {@code request-timeout} can be enforced on a source that ignores it. Only
     * ever as many concurrent tasks as there are drain workers, each of which is
     * blocked waiting for its own attempt.
     */
    private volatile ExecutorService attemptPool;

    private volatile boolean running;

    @Autowired
    public ComponentFactsResolver(
            FirewallComponentFactsJpaRepository factsRepository,
            ComponentFactsProperties properties,
            ObjectProvider<ComponentFactsSource> sources) {
        this(factsRepository, properties, sources.orderedStream().toList());
    }

    /** Visible for tests and for use without a Spring context. */
    public ComponentFactsResolver(
            FirewallComponentFactsJpaRepository factsRepository,
            ComponentFactsProperties properties,
            List<ComponentFactsSource> sources) {
        this.factsRepository = factsRepository;
        this.properties = properties;
        this.sourcesByPurlType = index(sources);
    }

    /**
     * Indexes the sources, and refuses to start on a duplicate.
     *
     * <p>Same reasoning as {@code FirewallRuleRegistry}: two beans claiming
     * {@code npm} is an ambiguity nobody wants resolved by bean ordering, and a
     * startup failure names the problem where a silent coin toss would surface
     * months later as "the license rule sometimes sees a different answer".
     */
    private static Map<String, ComponentFactsSource> index(List<ComponentFactsSource> sources) {
        Map<String, ComponentFactsSource> index = new LinkedHashMap<>();
        if (sources == null) {
            return Map.of();
        }
        for (ComponentFactsSource source : sources) {
            claim(index, source.purlType(), source);
            for (String alias : source.purlTypeAliases()) {
                claim(index, alias, source);
            }
        }
        return Map.copyOf(index);
    }

    private static void claim(
            Map<String, ComponentFactsSource> index, String purlType, ComponentFactsSource source) {
        if (purlType == null || purlType.isBlank()) {
            return;
        }
        String key = purlType.trim().toLowerCase(Locale.ROOT);
        ComponentFactsSource previous = index.put(key, source);
        if (previous != null && previous != source) {
            throw new IllegalStateException(
                    "Two component-facts sources claim purl type '%s': %s and %s"
                            .formatted(key, previous.getClass().getName(), source.getClass().getName()));
        }
    }

    @PostConstruct
    void start() {
        if (!properties.enabled()) {
            log.info("Component facts resolution is disabled "
                    + "(megarepo.firewall.facts.enabled=false) — MIN_AGE and LICENSE will report "
                    + "indeterminate for anything not already resolved");
            return;
        }
        if (sourcesByPurlType.isEmpty()) {
            log.warn("Component facts resolution is enabled but no source is configured — "
                    + "every component will settle as UNAVAILABLE");
        }
        running = true;
        attemptPool = Executors.newCachedThreadPool(namedDaemonFactory("firewall-facts-attempt-"));
        int threads = properties.threads();
        drainPool = Executors.newFixedThreadPool(threads, namedDaemonFactory("firewall-facts-"));
        for (int i = 0; i < threads; i++) {
            drainPool.submit(this::drainLoop);
        }
        log.info("Component facts resolver started with {} thread(s) and {} source(s): {}",
                threads, sourcesByPurlType.size(), sourcesByPurlType.keySet());
    }

    @PreDestroy
    void stop() {
        running = false;
        shutdown(drainPool);
        shutdown(attemptPool);
    }

    private static void shutdown(ExecutorService pool) {
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    /**
     * Queues one component for background resolution.
     *
     * <p>Idempotent and non-blocking. A component already queued or already being
     * resolved is not queued twice.
     */
    public void enqueue(String coordinates) {
        if (!properties.enabled() || coordinates == null || coordinates.isBlank()) {
            return;
        }
        if (inFlight.add(coordinates)) {
            queue.offer(coordinates);
        }
    }

    /** How much work is waiting. Exposed for the task log and for tests. */
    public int queueDepth() {
        return queue.size();
    }

    /**
     * The V19 task's entry point: enqueue everything the in-process queue cannot
     * be trusted to still hold.
     *
     * <p>Unresolved rows first — those are components something is waiting on —
     * then settled rows past {@code refresh-interval}. Publication dates never
     * change, but declared licenses get re-published and an {@code UNAVAILABLE}
     * verdict can be the result of an outage rather than of the ecosystem.
     *
     * @return how many rows were queued
     */
    public int sweep() {
        if (!properties.enabled()) {
            log.debug("Component facts resolution is disabled — sweep does nothing");
            return 0;
        }
        int batch = properties.batchSize();
        int queued = 0;
        try {
            for (FirewallComponentFactsEntity row :
                    factsRepository.findUnresolved(UNRESOLVED, PageRequest.of(0, batch))) {
                enqueue(row.getPurl());
                queued++;
            }
            int remaining = batch - queued;
            if (remaining > 0) {
                Instant staleBefore = Instant.now().minus(properties.refreshInterval());
                for (FirewallFactsState settled :
                        List.of(FirewallFactsState.RESOLVED, FirewallFactsState.UNAVAILABLE)) {
                    if (remaining <= 0) {
                        break;
                    }
                    for (FirewallComponentFactsEntity row : factsRepository.findStale(
                            settled, staleBefore, PageRequest.of(0, remaining))) {
                        enqueue(row.getPurl());
                        queued++;
                        remaining--;
                    }
                }
            }
        } catch (RuntimeException e) {
            // The task should show red on a database that is gone, but a partial
            // sweep is still a sweep and the next tick is ten minutes away.
            log.warn("Component facts sweep failed after queueing {} row(s)", queued, e);
            throw e;
        }
        log.info("Component facts sweep queued {} row(s); {} waiting", queued, queueDepth());
        return queued;
    }

    private void drainLoop() {
        while (running) {
            String coordinates;
            try {
                coordinates = queue.poll(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (coordinates == null) {
                continue;
            }
            try {
                resolveNow(coordinates);
            } catch (Throwable t) {
                // One ecosystem's bad day is not the queue's. Nothing thrown by a
                // source may end this worker.
                log.warn("Component facts resolution of {} failed unexpectedly", coordinates, t);
            } finally {
                inFlight.remove(coordinates);
            }
        }
    }

    /**
     * Resolves one component and writes the outcome. Synchronous; the drain
     * workers and the tests are the callers.
     *
     * @return the row as it now stands, or empty when there is nothing to write
     *     to (the coordinates are not a purl and no row could be created)
     */
    public Optional<FirewallComponentFactsEntity> resolveNow(String coordinates) {
        PackageURL purl;
        try {
            purl = new PackageURL(coordinates);
        } catch (MalformedPackageURLException e) {
            log.warn("Not a purl, so nothing can resolve it: {}", coordinates);
            return Optional.of(settleUnavailable(loadOrCreate(coordinates, null), "not a purl"));
        }

        FirewallComponentFactsEntity row = loadOrCreate(coordinates, purl.getType());
        ComponentFactsSource source = sourceFor(purl.getType());
        if (source == null) {
            // Raw files, Docker layers, an ecosystem MegaRepo does not proxy: a
            // settled "no ecosystem to ask", not a pending one.
            return Optional.of(settleUnavailable(
                    row, "no component-facts source for purl type " + purl.getType()));
        }

        row.setState(FirewallFactsState.PENDING);
        row.setUpdatedAt(Instant.now());
        row = save(row);

        try {
            Optional<ResolvedFacts> resolved = attempt(source, purl);
            if (resolved.isEmpty()) {
                return Optional.of(settleUnavailable(
                        row, "the " + purl.getType() + " ecosystem publishes no facts for this version"));
            }
            return Optional.of(settleResolved(row, resolved.get()));
        } catch (ComponentFactsSource.ComponentFactsException e) {
            return Optional.of(recordFailedAttempt(row, e));
        } catch (RuntimeException e) {
            // A source bug is counted like a failed fetch rather than propagated:
            // the alternative is one broken format module stopping the resolution
            // of every other ecosystem.
            return Optional.of(recordFailedAttempt(row, e));
        }
    }

    /**
     * Runs the source with {@code request-timeout} enforced from outside it.
     *
     * <p>A source is a format module's code calling a third-party registry. Most
     * of them inherit a timeout from the shared HTTP client, but "most" is not a
     * guarantee, and one source hanging forever would occupy a drain thread
     * permanently — with {@code threads} defaulting to 2, two such hangs are the
     * whole resolver.
     */
    private Optional<ResolvedFacts> attempt(ComponentFactsSource source, PackageURL purl)
            throws ComponentFactsSource.ComponentFactsException {
        ExecutorService pool = attemptPool;
        if (pool == null) {
            // Not started (tests, or resolution disabled): run inline rather than
            // pretend a timeout is being enforced.
            return source.resolve(purl);
        }
        Future<Optional<ResolvedFacts>> future = pool.submit(() -> source.resolve(purl));
        try {
            return future.get(properties.requestTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new ComponentFactsSource.ComponentFactsException(
                    "Timed out after %s resolving %s".formatted(properties.requestTimeout(), purl), e);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new ComponentFactsSource.ComponentFactsException("Interrupted resolving " + purl, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ComponentFactsSource.ComponentFactsException known) {
                throw known;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new ComponentFactsSource.ComponentFactsException("Failed resolving " + purl, cause);
        }
    }

    private FirewallComponentFactsEntity settleResolved(
            FirewallComponentFactsEntity row, ResolvedFacts facts) {
        row.setState(FirewallFactsState.RESOLVED);
        row.setPublishedAt(facts.publishedAt());
        row.setDeclaredLicenses(facts.declaredLicenses().toArray(String[]::new));
        row.setLicenseSource(licenseSource(facts));
        row.setSource(truncate(facts.source(), MAX_SOURCE_ID));
        row.setFetchedAt(Instant.now());
        row.setErrorMessage(null);
        row.setUpdatedAt(Instant.now());
        if (log.isDebugEnabled()) {
            log.debug("Resolved facts for {}: published {}, licenses {}",
                    row.getPurl(), facts.publishedAt(), facts.declaredLicenses());
        }
        return save(row);
    }

    /**
     * A license source outside the two the design allows is dropped rather than
     * written.
     *
     * <p>{@code firewall_component_facts} has a CHECK constraint on the column
     * precisely so that "we detected it from a LICENSE file" cannot appear
     * without arguing against the design first. Letting the write fail would turn
     * a scope violation in one format module into a resolver that cannot store
     * anything for that ecosystem; dropping it keeps the facts and loses only the
     * unsupported provenance label.
     */
    private static String licenseSource(ResolvedFacts facts) {
        String declared = facts.licenseSource();
        if (declared == null || facts.declaredLicenses().isEmpty()) {
            return null;
        }
        if (!LICENSE_SOURCES.contains(declared)) {
            log.warn("Ignoring unsupported license source '{}' from {} — declared metadata only",
                    declared, facts.source());
            return null;
        }
        return declared;
    }

    private FirewallComponentFactsEntity settleUnavailable(
            FirewallComponentFactsEntity row, String reason) {
        if (row == null) {
            return null;
        }
        row.setState(FirewallFactsState.UNAVAILABLE);
        row.setFetchedAt(Instant.now());
        row.setErrorMessage(truncate(reason, MAX_ERROR_MESSAGE));
        row.setUpdatedAt(Instant.now());
        return save(row);
    }

    private FirewallComponentFactsEntity recordFailedAttempt(
            FirewallComponentFactsEntity row, Exception e) {
        int attempts = row.getAttempts() + 1;
        row.setAttempts(attempts);
        row.setErrorMessage(truncate(describe(e), MAX_ERROR_MESSAGE));
        row.setUpdatedAt(Instant.now());
        if (attempts >= properties.maxAttempts()) {
            log.info("Giving up on component facts for {} after {} attempt(s): {}",
                    row.getPurl(), attempts, row.getErrorMessage());
            row.setState(FirewallFactsState.UNAVAILABLE);
            row.setFetchedAt(Instant.now());
        } else {
            // Back to UNKNOWN, not left PENDING: both are picked up by the sweep,
            // but only one of them is honest about there being no attempt running.
            log.debug("Component facts attempt {} of {} failed for {}: {}",
                    attempts, properties.maxAttempts(), row.getPurl(), row.getErrorMessage());
            row.setState(FirewallFactsState.UNKNOWN);
        }
        return save(row);
    }

    private FirewallComponentFactsEntity loadOrCreate(String coordinates, String purlType) {
        return factsRepository
                .findById(coordinates)
                .orElseGet(() -> {
                    FirewallComponentFactsEntity created = new FirewallComponentFactsEntity();
                    created.setPurl(coordinates);
                    created.setPurlType(purlType == null ? "unknown" : purlType);
                    created.setState(FirewallFactsState.UNKNOWN);
                    created.setCreatedAt(Instant.now());
                    created.setUpdatedAt(Instant.now());
                    return created;
                });
    }

    private FirewallComponentFactsEntity save(FirewallComponentFactsEntity row) {
        return factsRepository.save(row);
    }

    ComponentFactsSource sourceFor(String purlType) {
        if (purlType == null) {
            return null;
        }
        return sourcesByPurlType.get(purlType.trim().toLowerCase(Locale.ROOT));
    }

    /** Which purl types have a source. For diagnostics and tests. */
    public Set<String> supportedPurlTypes() {
        return sourcesByPurlType.keySet();
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        String cause = e.getCause() == null ? null : e.getCause().getMessage();
        if (message == null) {
            message = e.getClass().getSimpleName();
        }
        return cause == null || cause.equals(message) ? message : message + ": " + cause;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static ThreadFactory namedDaemonFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
