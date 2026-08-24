package de.bsnsoft.megarepo.repository.firewall;

import de.bsnsoft.megarepo.core.firewall.FirewallMode;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Configuration of the Phase 1 firewall observation path.
 *
 * <p>Nothing here can make the firewall block. Every value tunes how much work
 * the observer is allowed to do and how often it is allowed to write a row; the
 * "serve anyway" behaviour of AUDIT is structural (see
 * {@link FirewallDownloadObserver}) and not a setting.
 *
 * <p>Binds from environment variables through Spring Boot's relaxed binding:
 * <pre>
 *   MEGAREPO_FIREWALL_AUDIT_ENABLED=true
 *   MEGAREPO_FIREWALL_AUDIT_DEFAULTMODE=AUDIT
 * </pre>
 *
 * @param enabled master kill switch for the request-path observation. Off means
 *     the router's hook returns immediately and no query is issued at all — the
 *     escape hatch if the observation ever costs more than it is worth.
 * @param defaultMode mode for a repository that has no
 *     {@code firewall_repository_config} row. Ships as {@link FirewallMode#OFF}
 *     so that deploying this build changes the behaviour of no existing
 *     installation: a repository is observed only once an operator opts it in,
 *     either by creating a config row (whose column default is {@code AUDIT}) or
 *     by setting this property. Phase 2 backfills an explicit row per repository
 *     and this fallback becomes dead weight.
 * @param threads size of the observation pool. Small on purpose — this work is
 *     never on the critical path, and a large pool would just let a download
 *     burst turn into a database burst.
 * @param queueCapacity bounded backlog. When it is full, observations are
 *     dropped and counted rather than queued without limit or run on the caller:
 *     losing samples is acceptable, slowing downloads is not.
 * @param suppressionWindow how long an already-recorded finding for the same
 *     repository and component suppresses another identical row. See
 *     {@link FirewallViolationRecorder} for what "identical" means.
 * @param reevaluationInterval how long a downloaded path stays "recently
 *     evaluated" in memory, shedding repeat lookups for hot artifacts. Per node
 *     and best-effort; see {@link EvaluationThrottle}.
 * @param reevaluationCacheSize upper bound on entries in that in-memory set.
 */
@ConfigurationProperties(prefix = "megarepo.firewall.audit")
public record FirewallAuditProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("OFF") FirewallMode defaultMode,
        @DefaultValue("2") int threads,
        @DefaultValue("500") int queueCapacity,
        @DefaultValue("24h") Duration suppressionWindow,
        @DefaultValue("10m") Duration reevaluationInterval,
        @DefaultValue("10000") int reevaluationCacheSize) {

    public FirewallAuditProperties {
        defaultMode = defaultMode == null ? FirewallMode.OFF : defaultMode;
        threads = Math.clamp(threads, 1, 16);
        queueCapacity = Math.clamp(queueCapacity, 1, 100_000);
        suppressionWindow = nonNegative(suppressionWindow, Duration.ofHours(24));
        reevaluationInterval = nonNegative(reevaluationInterval, Duration.ofMinutes(10));
        reevaluationCacheSize = Math.clamp(reevaluationCacheSize, 0, 1_000_000);
    }

    /** Defaults — the shape a deployment that never configured the firewall gets. */
    public static FirewallAuditProperties defaults() {
        return new FirewallAuditProperties(
                true,
                FirewallMode.OFF,
                2,
                500,
                Duration.ofHours(24),
                Duration.ofMinutes(10),
                10_000);
    }

    private static Duration nonNegative(Duration value, Duration fallback) {
        if (value == null) {
            return fallback;
        }
        return value.isNegative() ? Duration.ZERO : value;
    }
}
