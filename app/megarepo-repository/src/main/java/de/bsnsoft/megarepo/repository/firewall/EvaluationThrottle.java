package de.bsnsoft.megarepo.repository.firewall;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers which download paths were evaluated recently, so a hot artifact does
 * not re-run the advisory lookup on every single request.
 *
 * <h2>Why this exists on top of the database-level de-duplication</h2>
 *
 * {@link FirewallViolationRecorder} keeps the <em>table</em> clean, but it only
 * gets to run after the lookup has already happened — and the expensive case is
 * the one it never sees. A popular <em>clean</em> component produces no
 * violation row at all, so nothing about it is on file, so every one of ten
 * thousand hourly downloads would pay for a full advisory lookup to rediscover
 * that it is clean. This set is what makes that cost proportional to the number
 * of distinct artifacts rather than to the number of requests.
 *
 * <p>The two layers have different jobs and different guarantees:
 * de-duplication is durable and shared, this is per-node and disposable. Losing
 * it — restart, eviction, a second node — costs redundant work and nothing else.
 * No audit record depends on it.
 *
 * <h2>Key</h2>
 *
 * {@code repositoryId|path}, not the component identity: the identity is only
 * known after two database queries, and shedding those is half the point. The
 * jar, the pom and the sources of one component therefore each hold their own
 * slot, which is fine — they converge on the same purl one layer down.
 *
 * <h2>Eviction</h2>
 *
 * Deliberately crude. Entries expire by age; when the map hits its ceiling the
 * expired ones are swept, and if that does not free anything the whole map is
 * dropped. A proper LRU would buy accuracy this cache does not need — the worst
 * outcome of a wrong answer is one extra advisory lookup.
 *
 * <p>Thread-safe.
 */
final class EvaluationThrottle {

    private final ConcurrentHashMap<String, Instant> lastEvaluated = new ConcurrentHashMap<>();
    private final Duration interval;
    private final int maxEntries;
    private final Clock clock;

    EvaluationThrottle(Duration interval, int maxEntries) {
        this(interval, maxEntries, Clock.systemUTC());
    }

    /** Visible for tests, which need to move time without sleeping. */
    EvaluationThrottle(Duration interval, int maxEntries, Clock clock) {
        this.interval = interval == null || interval.isNegative() ? Duration.ZERO : interval;
        this.maxEntries = Math.max(0, maxEntries);
        this.clock = clock;
    }

    /**
     * Claims the right to evaluate {@code repositoryId|path} and reports whether
     * it was granted.
     *
     * <p>Claiming and answering are one step on purpose: two threads racing on
     * the same artifact must not both be told to go ahead.
     *
     * @return {@code true} if this caller should evaluate; {@code false} if
     *     another evaluation happened within the interval
     */
    boolean claim(String repositoryId, String path) {
        if (interval.isZero() || maxEntries == 0) {
            // Throttling switched off: always evaluate.
            return true;
        }
        Instant now = clock.instant();
        Instant cutoff = now.minus(interval);
        String key = repositoryId + "|" + path;

        // compute() is atomic on ConcurrentHashMap, so of two threads arriving
        // together exactly one sees the absent/expired entry. Comparing the
        // returned value against `now` would not be enough: a frozen or
        // coarse-grained clock makes the previous timestamp equal to this one.
        boolean[] granted = {false};
        lastEvaluated.compute(key, (ignored, existing) -> {
            if (existing == null || existing.isBefore(cutoff)) {
                granted[0] = true;
                return now;
            }
            return existing;
        });
        if (granted[0]) {
            evictIfFull();
        }
        return granted[0];
    }

    private void evictIfFull() {
        if (lastEvaluated.size() <= maxEntries) {
            return;
        }
        Instant cutoff = clock.instant().minus(interval);
        for (Iterator<Map.Entry<String, Instant>> it = lastEvaluated.entrySet().iterator(); it.hasNext(); ) {
            if (it.next().getValue().isBefore(cutoff)) {
                it.remove();
            }
        }
        if (lastEvaluated.size() > maxEntries) {
            // Everything in here is still fresh. Dropping the lot costs one
            // round of redundant lookups and keeps the memory bound absolute.
            lastEvaluated.clear();
        }
    }

    /** Diagnostics and tests. */
    int size() {
        return lastEvaluated.size();
    }

    /** Diagnostics and tests. */
    void clear() {
        lastEvaluated.clear();
    }
}
