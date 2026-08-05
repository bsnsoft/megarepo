package de.bsnsoft.megarepo.repository.firewall;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Deployment-side configuration of firewall <em>enforcement</em>.
 *
 * <p>Binds from environment variables through Spring Boot's relaxed binding:
 * <pre>
 *   MEGAREPO_FIREWALL_ENFORCEMENT_ENABLED=true
 * </pre>
 *
 * @param enabled the master switch, <b>off by default</b>. This is only the
 *     <em>fallback</em> value: once an operator has written the
 *     {@code firewall_enforcement_settings} row, that row wins and this property
 *     is ignored — the same layering {@code megarepo.outbound-proxy.*} uses. See
 *     {@link FirewallEnforcementSettingsService}. Off means the firewall records
 *     and never denies, regardless of any repository's mode.
 * @param evaluationTimeout how long a download may wait for a verdict. The
 *     evaluation is local-database-only and normally takes milliseconds; this
 *     bounds the pathological case (a lock, a saturated pool, a hung query) so a
 *     firewall problem cannot become an unbounded stall on the request path.
 *     Exceeding it is not an answer of "allowed" — it hands the decision to the
 *     repository's {@code fail_mode}.
 * @param settingsRefreshInterval how long the master switch is cached in memory.
 *     The switch is read on every single download, so it is not fetched from the
 *     database each time; a change made through the API is pushed into the cache
 *     immediately, and this interval only bounds how long a change made by
 *     another node (or written straight to the table) takes to be noticed. It is
 *     what makes the switch runtime-settable without a restart.
 * @param threads size of the pool that runs the evaluation off the request
 *     thread. Larger than the audit pool because this one is on the critical
 *     path: a download in an enforcing repository waits for it.
 * @param queueCapacity bounded backlog. A rejected evaluation is an
 *     "unavailable" verdict and hands over to {@code fail_mode} rather than
 *     queueing the request path behind an audit backlog.
 */
@ConfigurationProperties(prefix = "megarepo.firewall.enforcement")
public record FirewallEnforcementProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("2s") Duration evaluationTimeout,
        @DefaultValue("10s") Duration settingsRefreshInterval,
        @DefaultValue("4") int threads,
        @DefaultValue("200") int queueCapacity) {

    public FirewallEnforcementProperties {
        evaluationTimeout = positive(evaluationTimeout, Duration.ofSeconds(2));
        settingsRefreshInterval = nonNegative(settingsRefreshInterval, Duration.ofSeconds(10));
        threads = Math.clamp(threads, 1, 64);
        queueCapacity = Math.clamp(queueCapacity, 1, 100_000);
    }

    /** Defaults — the shape a deployment that never configured enforcement gets. */
    public static FirewallEnforcementProperties defaults() {
        return new FirewallEnforcementProperties(
                false, Duration.ofSeconds(2), Duration.ofSeconds(10), 4, 200);
    }

    /** The same defaults with the master switch pre-armed. For tests. */
    public static FirewallEnforcementProperties enforcing() {
        return new FirewallEnforcementProperties(
                true, Duration.ofSeconds(2), Duration.ofSeconds(10), 4, 200);
    }

    private static Duration positive(Duration value, Duration fallback) {
        if (value == null || value.isNegative() || value.isZero()) {
            return fallback;
        }
        return value;
    }

    private static Duration nonNegative(Duration value, Duration fallback) {
        if (value == null) {
            return fallback;
        }
        return value.isNegative() ? Duration.ZERO : value;
    }
}
