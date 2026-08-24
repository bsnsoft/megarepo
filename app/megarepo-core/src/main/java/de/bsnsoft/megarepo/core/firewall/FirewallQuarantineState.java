package de.bsnsoft.megarepo.core.firewall;

/**
 * Where one component stands in one repository's quarantine.
 *
 * <p>Persisted as the enum name in {@code firewall_quarantine.state}, guarded by
 * a CHECK constraint: unlike {@link FirewallRuleType} this is a closed set, and a
 * fourth state would be a behaviour change rather than a new rule.
 *
 * <h2>What quarantine is, and what it is not</h2>
 *
 * Quarantine is not "everything the policy denied". A component denied for a
 * critical advisory or because it is malicious is simply refused — waiting does
 * not make it acceptable, and holding it in a queue only invites someone to
 * click "release" on a package that exists to steal credentials.
 *
 * <p>Quarantine is for the verdicts that are <em>expected to change on their
 * own</em>: the component is too new to trust yet, nothing is known about it
 * yet, or the firewall could not finish evaluating it and the repository is
 * fail-closed. All three resolve with time or with data, which is why they get a
 * state machine and a re-evaluation schedule instead of a flat refusal. The
 * customer's rule is explicit: quarantine is rule-driven, never blanket.
 *
 * <h2>Transitions</h2>
 *
 * <pre>
 *   (new)  --rule--&gt;  QUARANTINED  --re-evaluation clean / exemption / operator--&gt;  RELEASED
 *                          |
 *                          '--re-evaluation finds a blocking violation / operator--&gt;  BLOCKED
 * </pre>
 *
 * {@link #RELEASED} and {@link #BLOCKED} are terminal for the entry as it
 * stands; an operator changing a policy or granting an exemption produces a new
 * decision on the same row, recorded with its
 * {@link FirewallQuarantineResolution} and {@code decided_by}. Nothing ever goes
 * back to {@link #QUARANTINED} without a fresh trigger, because "held again"
 * without a reason is indistinguishable from a stuck queue.
 */
public enum FirewallQuarantineState {

    /**
     * Held: the download is refused for now, and the entry is re-evaluated on a
     * schedule and after every advisory sync.
     */
    QUARANTINED,

    /**
     * Cleared: the component may be served. Either a re-evaluation found nothing
     * blocking any more, an exemption covers it, or an operator released it —
     * {@code resolution} says which.
     */
    RELEASED,

    /**
     * Refused for good: a re-evaluation found a blocking violation, or an
     * operator rejected it. Unlike {@link #QUARANTINED} this is not re-evaluated
     * hoping for a different answer; it changes when the policy or an exemption
     * changes.
     */
    BLOCKED;

    /** Whether a download of this component must be refused while in this state. */
    public boolean denies() {
        return this != RELEASED;
    }

    /** Whether the scheduled re-evaluation should look at entries in this state. */
    public boolean reevaluated() {
        return this == QUARANTINED;
    }
}
