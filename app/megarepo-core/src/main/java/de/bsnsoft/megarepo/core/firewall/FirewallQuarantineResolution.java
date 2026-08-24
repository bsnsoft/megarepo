package de.bsnsoft.megarepo.core.firewall;

/**
 * How a quarantine entry left {@link FirewallQuarantineState#QUARANTINED}.
 *
 * <p>Persisted as the enum name in {@code firewall_quarantine.resolution},
 * unconstrained for the same reason as {@link FirewallQuarantineReason}.
 *
 * <p>The customer asked for automatic release "with a recorded reason", and this
 * is that reason in machine-readable form — the free-text
 * {@code decision_reason} beside it is for the human sentence. Both, not one: a
 * release queue that only says "released 14:22 by system" cannot answer the one
 * question an auditor asks, which is whether the thing that made the component
 * unacceptable actually went away or whether somebody just got tired of the
 * ticket.
 */
public enum FirewallQuarantineResolution {

    /**
     * A re-evaluation ran the current policy against current data and nothing
     * matched any more. The generic automatic release.
     */
    RE_EVALUATED_CLEAN,

    /**
     * The component reached the minimum age its policy demands. The specific
     * automatic release for {@link FirewallQuarantineReason#MIN_AGE_NOT_MET},
     * kept apart from {@link #RE_EVALUATED_CLEAN} because it is the one release
     * that was predictable from the moment the entry was created.
     */
    AGE_REACHED,

    /**
     * Advisory data for the component arrived, and it cleared the rule that had
     * held it — the resolution for {@link FirewallQuarantineReason#UNKNOWN_COMPONENT}
     * and for {@link FirewallQuarantineReason#EVALUATION_INCOMPLETE}.
     */
    ADVISORY_DATA_ARRIVED,

    /** An approved exemption now covers the component. */
    EXEMPTION_GRANTED,

    /** The policy changed — a rule was disabled, retuned, or unassigned. */
    POLICY_CHANGED,

    /** An operator released it from the quarantine queue. */
    MANUAL_RELEASE,

    /**
     * A re-evaluation found a blocking violation. Moves the entry to
     * {@link FirewallQuarantineState#BLOCKED}.
     */
    POLICY_VIOLATION,

    /** An operator rejected it from the quarantine queue. */
    MANUAL_BLOCK;

    /** Whether this resolution ends in {@link FirewallQuarantineState#RELEASED}. */
    public boolean releases() {
        return this != POLICY_VIOLATION && this != MANUAL_BLOCK;
    }

    /** Whether a person decided this, as opposed to the scheduled re-evaluation. */
    public boolean isManual() {
        return this == MANUAL_RELEASE || this == MANUAL_BLOCK;
    }
}
