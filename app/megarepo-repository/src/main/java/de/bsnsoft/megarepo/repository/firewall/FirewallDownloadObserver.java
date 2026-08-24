package de.bsnsoft.megarepo.repository.firewall;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The firewall's one and only attachment to the request path.
 *
 * <h2>Why this class can not break a download</h2>
 *
 * Three independent reasons, in the order they take effect:
 *
 * <ol>
 *   <li><b>It runs after the response.</b> {@code RepositoryRouter} calls
 *       {@link #observeDownload} once the artifact has already been streamed to
 *       the client, next to the existing download audit entry. At that point
 *       there is physically no response left to withhold or alter.</li>
 *   <li><b>It returns nothing.</b> The method is {@code void}. There is no
 *       verdict for a caller to act on, which is what makes "AUDIT never blocks"
 *       a property of the type rather than a discipline the caller has to
 *       keep.</li>
 *   <li><b>It hands the work to another thread and returns.</b> The caller never
 *       waits for a query, a lock or a transaction — and never for a network
 *       call, which the firewall does not make at all (advisory feeds are pulled
 *       by a background task; the lookup reads the local mirror).</li>
 * </ol>
 *
 * <p>On top of that every entry point swallows {@link RuntimeException}. A
 * firewall defect degrades the audit trail, never the repository.
 *
 * <h2>Saturation</h2>
 *
 * The pool is small and its queue is bounded. When the queue is full,
 * observations are <em>dropped and counted</em> — not run on the calling thread
 * (that would push the backlog straight onto the request path) and not queued
 * without limit (that would trade a latency problem for a heap problem). Under a
 * download burst the audit trail thins out; nothing else changes. The drop count
 * is logged so the thinning is visible rather than silent.
 */
@Component
public class FirewallDownloadObserver {

    private static final Logger log = LoggerFactory.getLogger(FirewallDownloadObserver.class);

    /** Drops are logged on the first one, then every this many. */
    private static final long DROP_LOG_INTERVAL = 1_000;

    private final FirewallEvaluationService evaluation;
    private final FirewallAuditProperties properties;
    private final EvaluationThrottle throttle;
    private final Executor executor;
    private final ExecutorService ownedExecutor;
    private final AtomicLong dropped = new AtomicLong();

    @Autowired
    public FirewallDownloadObserver(
            FirewallEvaluationService evaluation, FirewallAuditProperties properties) {
        this.evaluation = evaluation;
        this.properties = properties;
        this.throttle = new EvaluationThrottle(
                properties.reevaluationInterval(), properties.reevaluationCacheSize());
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                properties.threads(),
                properties.threads(),
                60,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(properties.queueCapacity()),
                daemonThreadFactory(),
                (task, rejectingPool) -> countDrop());
        pool.allowCoreThreadTimeOut(true);
        this.ownedExecutor = pool;
        this.executor = pool;
    }

    /**
     * Visible for tests, which run the evaluation on the calling thread so an
     * assertion does not have to wait for a pool.
     */
    FirewallDownloadObserver(
            FirewallEvaluationService evaluation, FirewallAuditProperties properties, Executor executor) {
        this.evaluation = evaluation;
        this.properties = properties;
        this.throttle = new EvaluationThrottle(
                properties.reevaluationInterval(), properties.reevaluationCacheSize());
        this.executor = executor;
        this.ownedExecutor = null;
    }

    /**
     * Notes that an artifact was served, and — off this thread — records any
     * advisories that name it.
     *
     * <p>Returns immediately and never throws. Callers must not treat the return
     * as permission for anything; there is none.
     */
    public void observeDownload(
            UUID repositoryId, String repositoryName, String path, FirewallRequestContext context) {
        try {
            if (!properties.enabled() || repositoryId == null || path == null) {
                return;
            }
            if (!throttle.claim(repositoryId.toString(), path)) {
                return;
            }
            executor.execute(() -> evaluateQuietly(repositoryId, repositoryName, path, context));
        } catch (RuntimeException e) {
            // Includes a RejectedExecutionException from an executor supplied by
            // a caller that does not use the drop handler above.
            countDrop();
            log.debug("Firewall AUDIT observation of {}/{} was not scheduled", repositoryName, path, e);
        }
    }

    private void evaluateQuietly(
            UUID repositoryId, String repositoryName, String path, FirewallRequestContext context) {
        try {
            evaluation.evaluateDownload(repositoryId, repositoryName, path, context);
        } catch (RuntimeException e) {
            // evaluateDownload already swallows its own failures; this is the
            // belt to that braces, so a pool thread can never die of firewall work.
            log.warn("Firewall AUDIT observation of {}/{} failed", repositoryName, path, e);
        }
    }

    private void countDrop() {
        long count = dropped.incrementAndGet();
        if (count == 1 || count % DROP_LOG_INTERVAL == 0) {
            log.warn("Firewall AUDIT observation dropped — backlog full ({} dropped so far). "
                    + "Downloads are unaffected; the audit trail is incomplete.", count);
        }
    }

    /** How many observations were dropped for lack of capacity. Diagnostics and tests. */
    public long droppedCount() {
        return dropped.get();
    }

    @PreDestroy
    void shutdown() {
        if (ownedExecutor == null) {
            return;
        }
        // Audit work is disposable: nothing waits on it and nothing is lost that
        // the next download will not produce again.
        ownedExecutor.shutdownNow();
        try {
            if (!ownedExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                log.debug("Firewall AUDIT pool did not terminate within 5s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static ThreadFactory daemonThreadFactory() {
        AtomicLong counter = new AtomicLong();
        return runnable -> {
            Thread thread = new Thread(runnable, "firewall-audit-" + counter.incrementAndGet());
            // Daemon: a stuck observation must never keep the JVM from shutting down.
            thread.setDaemon(true);
            return thread;
        };
    }
}
