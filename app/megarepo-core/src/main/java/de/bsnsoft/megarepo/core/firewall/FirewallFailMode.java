package de.bsnsoft.megarepo.core.firewall;

/**
 * What the firewall does when it cannot reach a verdict — no advisory data for
 * the component, the advisory store is stale, or evaluation itself failed.
 *
 * <p>Persisted as the enum name in {@code firewall_repository_config.fail_mode}.
 */
public enum FirewallFailMode {

    /** Let the request through. Default: an unavailable firewall must not break builds. */
    FAIL_OPEN,

    /** Deny the request. Only meaningful once enforcement lands in Phase 2. */
    FAIL_CLOSED
}
