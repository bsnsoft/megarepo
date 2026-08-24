package de.bsnsoft.megarepo.repository.firewall.rule.corpus;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Deployment-side configuration of the name corpus the two heuristic rules read.
 *
 * <p>Binds from environment variables through Spring Boot's relaxed binding:
 * <pre>
 *   MEGAREPO_FIREWALL_CORPUS_REFRESH_INTERVAL=15m
 * </pre>
 *
 * <p>Everything here is about the <em>cost</em> of knowing which names this
 * instance holds. What the rules do with that knowledge is per-policy
 * configuration and lives in {@code firewall_policy_rule.config}, not here — an
 * operator tunes thresholds per repository policy and tunes the scan once per
 * deployment.
 *
 * @param enabled whether the corpus is built at all. Off leaves it permanently
 *     empty, which makes {@code TYPOSQUAT} inert and {@code NAMESPACE_CONFUSION}
 *     fall back to its explicitly configured namespaces — the honest shape of
 *     "I do not want this scan on my instance", and better than an operator
 *     achieving the same thing by setting the refresh interval to a year
 * @param refreshInterval how long a snapshot is used before a new scan is
 *     started behind it. The corpus changes when a new package is proxied for
 *     the first time, which is a scale of hours, not seconds; the download path
 *     never waits for the refresh either way
 * @param batchSize how many component rows one page of the scan reads
 * @param maxComponents the cap on how many component rows one scan reads. A full
 *     table scan is the price of a corpus with no external feed, and on a large
 *     instance it has to stay bounded: past this many rows the scan stops and
 *     the snapshot is marked truncated, which is logged. The default is generous
 *     enough that no realistic instance reaches it, and finite so that none can
 *     turn the background load into an unbounded one
 */
@ConfigurationProperties(prefix = "megarepo.firewall.corpus")
public record ComponentCorpusProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("30m") Duration refreshInterval,
        @DefaultValue("1000") int batchSize,
        @DefaultValue("500000") long maxComponents) {

    public ComponentCorpusProperties {
        refreshInterval = positive(refreshInterval, Duration.ofMinutes(30));
        batchSize = Math.clamp(batchSize, 50, 10_000);
        maxComponents = Math.clamp(maxComponents, 1_000L, 50_000_000L);
    }

    /** Defaults — the shape a deployment that never configured the corpus gets. */
    public static ComponentCorpusProperties defaults() {
        return new ComponentCorpusProperties(true, Duration.ofMinutes(30), 1000, 500_000L);
    }

    private static Duration positive(Duration value, Duration fallback) {
        if (value == null || value.isNegative() || value.isZero()) {
            return fallback;
        }
        return value;
    }
}
