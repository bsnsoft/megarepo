package de.bsnsoft.megarepo.repository.firewall;

import de.bsnsoft.megarepo.core.firewall.FirewallFailMode;
import de.bsnsoft.megarepo.core.firewall.FirewallMode;

import java.util.UUID;

/**
 * The resolved {@code firewall_repository_config} of one repository.
 *
 * @param mode the configured mode, verbatim. {@link FirewallMode#QUARANTINE} is
 *     kept as configured rather than rewritten to {@link FirewallMode#AUDIT} —
 *     {@link #evaluates()} decides what happens, and the recorded row states the
 *     configured mode so an operator can see that a repository is <em>asking</em>
 *     for enforcement it is not getting yet.
 * @param failMode read and recorded, never acted on in Phase 1. There is no
 *     verdict to fail open or closed on while nothing enforces, and
 *     {@link FirewallFailMode#FAIL_CLOSED} must not start denying downloads as a
 *     side effect of switching the observation on.
 * @param policyId the assigned policy, or null. Phase 1 evaluates no policy, so
 *     this never reaches {@code firewall_violation.policy_id}; it is carried
 *     only to be recorded as context.
 * @param explicit whether a {@code firewall_repository_config} row existed. False
 *     means the mode came from {@link FirewallAuditProperties#defaultMode()}.
 */
public record FirewallRepositorySettings(
        FirewallMode mode, FirewallFailMode failMode, UUID policyId, boolean explicit) {

    public FirewallRepositorySettings {
        mode = mode == null ? FirewallMode.OFF : mode;
        failMode = failMode == null ? FirewallFailMode.FAIL_OPEN : failMode;
    }

    /**
     * Whether the firewall looks at downloads for this repository at all.
     *
     * <p>{@link FirewallMode#QUARANTINE} answers true and behaves exactly like
     * {@link FirewallMode#AUDIT}: Phase 1 has no enforcement, so a repository set
     * to QUARANTINE is observed and recorded, and <em>nothing is held back</em>.
     * The alternative — refusing to evaluate a mode we cannot honour — would
     * leave the repositories that asked for the most protection with no data at
     * all.
     */
    public boolean evaluates() {
        return mode != FirewallMode.OFF;
    }

    /**
     * True when the configured mode promises more than Phase 1 delivers, i.e.
     * QUARANTINE. Recorded on the violation so no reader mistakes an observation
     * for a block.
     */
    public boolean enforcementDeferred() {
        return mode == FirewallMode.QUARANTINE;
    }

    /** Defaults for a repository with no row, given the configured fallback mode. */
    public static FirewallRepositorySettings fallback(FirewallMode defaultMode) {
        return new FirewallRepositorySettings(defaultMode, FirewallFailMode.FAIL_OPEN, null, false);
    }
}
