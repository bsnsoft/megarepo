package de.bsnsoft.megarepo.repository.firewall.exemption;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Deployment-side configuration of the exemption workflow.
 *
 * <p>Binds from environment variables, e.g.
 * {@code MEGAREPO_FIREWALL_EXEMPTION_DEFAULT_VALIDITY=P30D}.
 *
 * @param selfServiceRequests whether a developer who hits a block may file a
 *     request from the block page. On by default — the customer asked for it, and
 *     an exemption process that starts with a support ticket is a process people
 *     route around by copying the artifact somewhere else. A request still
 *     changes nothing until an approver acts
 * @param defaultValidity what the approval dialog pre-fills as the expiry. Not a
 *     cap and not applied silently: an approver can still choose "never", but
 *     they have to choose it. The V8 whitelist's problem was that "forever" was
 *     what you got by not thinking about it
 * @param expiryNoticeLead how far ahead of a lapse the notice goes out. Long
 *     enough that somebody can renew or fix the dependency inside a sprint
 * @param maxValidity the longest expiry the API accepts for a bounded exemption,
 *     as a guard against a typo turning P30D into P30Y. Null-safe: an explicit
 *     "never" is unaffected, because it is a different statement from "a very
 *     long time"
 */
@ConfigurationProperties(prefix = "megarepo.firewall.exemption")
public record ExemptionProperties(
        @DefaultValue("true") boolean selfServiceRequests,
        @DefaultValue("90d") Duration defaultValidity,
        @DefaultValue("7d") Duration expiryNoticeLead,
        @DefaultValue("3650d") Duration maxValidity) {

    public ExemptionProperties {
        defaultValidity = positive(defaultValidity, Duration.ofDays(90));
        expiryNoticeLead = positive(expiryNoticeLead, Duration.ofDays(7));
        maxValidity = positive(maxValidity, Duration.ofDays(3650));
        if (defaultValidity.compareTo(maxValidity) > 0) {
            defaultValidity = maxValidity;
        }
    }

    /** Defaults — the shape a deployment that never configured exemptions gets. */
    public static ExemptionProperties defaults() {
        return new ExemptionProperties(
                true, Duration.ofDays(90), Duration.ofDays(7), Duration.ofDays(3650));
    }

    private static Duration positive(Duration value, Duration fallback) {
        if (value == null || value.isNegative() || value.isZero()) {
            return fallback;
        }
        return value;
    }
}
