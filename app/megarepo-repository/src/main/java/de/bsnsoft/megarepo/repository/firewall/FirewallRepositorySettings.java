package de.bsnsoft.megarepo.repository.firewall;

import de.bsnsoft.megarepo.core.firewall.FirewallFailMode;
import de.bsnsoft.megarepo.core.firewall.FirewallMode;

import java.util.UUID;

/**
 * The resolved {@code firewall_repository_config} of one repository.
 *
 * <p>This is only <em>half</em> of the enforcement question. A repository set to
 * {@link FirewallMode#QUARANTINE} asks to be enforced; whether it actually is
 * depends on the global switch in {@link FirewallEnforcementSettingsService},
 * which is off by default. Both have to say yes, which is what keeps an upgrade
 * from starting to deny downloads for repositories that were set to QUARANTINE
 * back when the mode was inert.
 *
 * @param mode the configured mode, verbatim
 * @param failMode what happens when no verdict can be reached in time. Only
 *     consulted on the enforcement path — there is nothing to fail open or
 *     closed on while a repository is merely being observed, and
 *     {@link FirewallFailMode#FAIL_CLOSED} must not start denying downloads as a
 *     side effect of switching the observation on.
 * @param policyId the assigned policy, or null for the global default policy
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
     * <p>True for both {@link FirewallMode#AUDIT} and
     * {@link FirewallMode#QUARANTINE}: a repository that asks for enforcement it
     * is not currently getting is still observed, because refusing to evaluate a
     * mode we cannot honour would leave the repositories that asked for the most
     * protection with no data at all.
     */
    public boolean evaluates() {
        return mode != FirewallMode.OFF;
    }

    /**
     * Whether this repository asks for its policy to be enforced, i.e. is in
     * QUARANTINE mode.
     *
     * <p>Says nothing about whether it <em>is</em> enforced — the global switch
     * decides that.
     */
    public boolean enforces() {
        return mode == FirewallMode.QUARANTINE;
    }

    /**
     * Whether the configured mode promises more than is being delivered.
     *
     * @param enforcementEnabled the state of the global enforcement switch
     */
    public boolean enforcementDeferred(boolean enforcementEnabled) {
        return enforces() && !enforcementEnabled;
    }

    /** Whether a failure to reach a verdict must deny the download. */
    public boolean failsClosed() {
        return failMode == FirewallFailMode.FAIL_CLOSED;
    }

    /** Defaults for a repository with no row, given the configured fallback mode. */
    public static FirewallRepositorySettings fallback(FirewallMode defaultMode) {
        return new FirewallRepositorySettings(defaultMode, FirewallFailMode.FAIL_OPEN, null, false);
    }
}
