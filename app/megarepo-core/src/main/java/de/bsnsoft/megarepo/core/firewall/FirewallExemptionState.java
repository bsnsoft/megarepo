package de.bsnsoft.megarepo.core.firewall;

/**
 * Where an exemption stands in its approval life cycle.
 *
 * <p>Persisted as the enum name in {@code firewall_exemption.state}, guarded by
 * a CHECK constraint.
 *
 * <p>Only {@link #APPROVED} lets anything through, and only while
 * {@code expires_at} is in the future. Everything else — a request nobody has
 * looked at, a rejected one, an expired one — is a row that documents a decision
 * and changes no download.
 *
 * <h2>Why {@link #REVOKED} exists even though the design proposal had four states</h2>
 *
 * The proposal listed REQUESTED / APPROVED / REJECTED / EXPIRED. That leaves an
 * administrator who wants to take back an exemption before its expiry with two
 * options: delete the row, which destroys the audit trail of a decision that was
 * live in production, or backdate {@code expires_at}, which makes the log claim
 * the exemption lapsed on its own. Both are worse than a fifth constant.
 */
public enum FirewallExemptionState {

    /**
     * A developer asked for it from a block page; nobody has decided yet. Blocks
     * nothing and lets nothing through.
     */
    REQUESTED,

    /** Granted. The only state that can let a download past a policy. */
    APPROVED,

    /** Refused by an approver. Kept, so the next requester sees it was asked before. */
    REJECTED,

    /**
     * Was approved, and {@code expires_at} has passed. Set by the expiry sweep
     * rather than inferred at read time, so the queue and the audit trail agree
     * about when it stopped applying.
     *
     * <p>An expired exemption blocks again — that is the entire point of giving
     * exemptions an expiry, and the behaviour the V8 whitelist could not
     * express.
     */
    EXPIRED,

    /** Withdrawn by an administrator before it expired. */
    REVOKED;

    /** Whether an exemption in this state can suppress a policy violation. */
    public boolean grantsPassage() {
        return this == APPROVED;
    }

    /** Whether an approver still has to decide about it. */
    public boolean isPending() {
        return this == REQUESTED;
    }

    /** Whether the state is final — no further transition is expected. */
    public boolean isTerminal() {
        return this == REJECTED || this == EXPIRED || this == REVOKED;
    }
}
