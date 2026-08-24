package de.bsnsoft.megarepo.repository.firewall.quarantine;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Deployment-side configuration of the quarantine mechanism.
 *
 * <p>Binds from environment variables through Spring Boot's relaxed binding:
 * <pre>
 *   MEGAREPO_FIREWALL_QUARANTINE_ENABLED=false
 * </pre>
 *
 * @param enabled whether components are held at all. The customer asked for
 *     quarantine to be switchable off in its entirety, and this is that switch:
 *     off, an enforcing repository refuses or serves on the policy alone and no
 *     queue sits in between. Default on, because a repository put into
 *     QUARANTINE mode by an operator who then finds nothing is queued has been
 *     misled — but the master enforcement switch is still off by default, so
 *     this defaulting to true changes nothing about an upgrade
 * @param reevaluationBatchSize how many held entries one sweep looks at. Bounded
 *     so a queue that grew to five figures during an outage does not turn the
 *     scheduled task into an hour-long transaction
 * @param minReevaluationInterval the floor between two evaluations of the same
 *     entry. Stops an entry whose rule keeps saying "not yet" from being
 *     re-evaluated on every tick — a MIN_AGE entry is scheduled for the exact
 *     moment it becomes old enough, and everything else waits this long
 * @param maxReevaluationInterval the ceiling. An entry held for
 *     {@code UNKNOWN_COMPONENT} has no predictable release time, and without a
 *     ceiling an exponential backoff would eventually stop looking
 * @param reevaluateAfterAdvisorySync whether an advisory sync triggers a sweep.
 *     On by default: a sync is the single event most likely to change an
 *     {@code UNKNOWN_COMPONENT} answer, and waiting up to a quarter of an hour
 *     after it is a quarter of an hour of a build failing for data that has
 *     already arrived
 * @param retention how long decided entries are kept before a cleanup may remove
 *     them. Released and blocked rows are the audit trail of what the firewall
 *     did to which build; they are not garbage the moment they are decided
 */
@ConfigurationProperties(prefix = "megarepo.firewall.quarantine")
public record QuarantineProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("200") int reevaluationBatchSize,
        @DefaultValue("5m") Duration minReevaluationInterval,
        @DefaultValue("6h") Duration maxReevaluationInterval,
        @DefaultValue("true") boolean reevaluateAfterAdvisorySync,
        @DefaultValue("90d") Duration retention) {

    public QuarantineProperties {
        reevaluationBatchSize = Math.clamp(reevaluationBatchSize, 1, 10_000);
        minReevaluationInterval = positive(minReevaluationInterval, Duration.ofMinutes(5));
        maxReevaluationInterval = positive(maxReevaluationInterval, Duration.ofHours(6));
        if (maxReevaluationInterval.compareTo(minReevaluationInterval) < 0) {
            maxReevaluationInterval = minReevaluationInterval;
        }
        retention = positive(retention, Duration.ofDays(90));
    }

    /** Defaults — the shape a deployment that never configured quarantine gets. */
    public static QuarantineProperties defaults() {
        return new QuarantineProperties(
                true, 200, Duration.ofMinutes(5), Duration.ofHours(6), true, Duration.ofDays(90));
    }

    /** The same defaults with quarantine switched off. For tests. */
    public static QuarantineProperties disabled() {
        return new QuarantineProperties(
                false, 200, Duration.ofMinutes(5), Duration.ofHours(6), true, Duration.ofDays(90));
    }

    private static Duration positive(Duration value, Duration fallback) {
        if (value == null || value.isNegative() || value.isZero()) {
            return fallback;
        }
        return value;
    }
}
