package de.bsnsoft.megarepo.repository.firewall.quarantine;

import de.bsnsoft.megarepo.core.firewall.FirewallQuarantineResolution;

import java.util.Objects;
import java.util.UUID;

/**
 * Why a quarantine entry was released or blocked, and by whom.
 *
 * <p>The customer asked for automatic release "with a recorded reason". This is
 * that record, and it is required rather than optional: a queue whose entries
 * simply disappear cannot answer whether the thing that made a component
 * unacceptable actually went away.
 *
 * @param resolution the machine-readable answer — what changed
 * @param decidedBy who decided. A user name for an operator action, {@code system}
 *     for the scheduled re-evaluation. Never null and never blank: "released by
 *     nobody" is the one entry an auditor will ask about
 * @param note the human sentence, optional. For an automatic release it names the
 *     evidence ("published 2024-11-02, minimum age 7 days reached"); for a manual
 *     one it is whatever the operator typed
 * @param exemptionId the exemption that caused the release, when
 *     {@link FirewallQuarantineResolution#EXEMPTION_GRANTED} did it
 */
public record QuarantineDecision(
        FirewallQuarantineResolution resolution, String decidedBy, String note, UUID exemptionId) {

    /** Who the scheduled sweep records itself as. */
    public static final String SYSTEM = "system";

    public QuarantineDecision {
        Objects.requireNonNull(resolution, "resolution must not be null");
        if (decidedBy == null || decidedBy.isBlank()) {
            throw new IllegalArgumentException("decidedBy must name a user or 'system'");
        }
    }

    /** A decision taken by the scheduled re-evaluation. */
    public static QuarantineDecision automatic(FirewallQuarantineResolution resolution, String note) {
        return new QuarantineDecision(resolution, SYSTEM, note, null);
    }

    /** A decision taken by an operator. */
    public static QuarantineDecision manual(
            FirewallQuarantineResolution resolution, String user, String note) {
        return new QuarantineDecision(resolution, user, note, null);
    }

    /** A release caused by an approved exemption. */
    public static QuarantineDecision byExemption(UUID exemptionId, String decidedBy, String note) {
        return new QuarantineDecision(
                FirewallQuarantineResolution.EXEMPTION_GRANTED, decidedBy, note, exemptionId);
    }
}
