package de.bsnsoft.megarepo.repository.firewall.facts;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Deployment-side configuration of the component-facts resolver — the background
 * job that learns publication dates and declared licenses so that
 * {@code MIN_AGE} and {@code LICENSE} can be evaluated without leaving the
 * database on the request path.
 *
 * @param enabled whether facts are resolved at all. Off means every unresolved
 *     component stays {@code UNKNOWN}, which makes the two rules that need facts
 *     report "indeterminate" forever — correct for an air-gapped install that
 *     does not want the outbound traffic, and a deliberate choice rather than a
 *     silent degradation
 * @param preferLocalMetadata whether to read the artifact's own stored descriptor
 *     before asking an upstream registry. On by default: it costs no outbound
 *     request, works offline, and describes the exact artifact this instance
 *     serves rather than what the registry says today
 * @param threads size of the background resolver pool. Small on purpose — this
 *     work is never on a request path, and a large pool aimed at a public
 *     registry is how an instance gets rate-limited
 * @param batchSize how many rows one sweep drains
 * @param requestTimeout per-source timeout for one resolution attempt
 * @param maxAttempts how often a failing resolution is retried before the row is
 *     marked {@code UNAVAILABLE}. A permanently failing row that is retried
 *     forever is a background job that never gets to the rest of its queue
 * @param refreshInterval how long a settled row is trusted before a slow
 *     background refresh. Publication dates never change, but declared licenses
 *     get re-published and an {@code UNAVAILABLE} verdict can be the result of an
 *     outage. The request path never waits for this — a stale row is served as-is
 *     and refreshed behind it
 */
@ConfigurationProperties(prefix = "megarepo.firewall.facts")
public record ComponentFactsProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("true") boolean preferLocalMetadata,
        @DefaultValue("2") int threads,
        @DefaultValue("100") int batchSize,
        @DefaultValue("10s") Duration requestTimeout,
        @DefaultValue("5") int maxAttempts,
        @DefaultValue("30d") Duration refreshInterval) {

    public ComponentFactsProperties {
        threads = Math.clamp(threads, 1, 32);
        batchSize = Math.clamp(batchSize, 1, 10_000);
        maxAttempts = Math.clamp(maxAttempts, 1, 100);
        requestTimeout = positive(requestTimeout, Duration.ofSeconds(10));
        refreshInterval = positive(refreshInterval, Duration.ofDays(30));
    }

    /** Defaults — the shape a deployment that never configured the resolver gets. */
    public static ComponentFactsProperties defaults() {
        return new ComponentFactsProperties(
                true, true, 2, 100, Duration.ofSeconds(10), 5, Duration.ofDays(30));
    }

    private static Duration positive(Duration value, Duration fallback) {
        if (value == null || value.isNegative() || value.isZero()) {
            return fallback;
        }
        return value;
    }
}
