package de.bsnsoft.megarepo.core.firewall;

/**
 * What a policy rule does when it matches.
 *
 * <p>Persisted as the enum name in {@code firewall_policy_rule.action} and
 * {@code firewall_violation.action}, both guarded by a CHECK constraint — this
 * is a closed set, unlike {@link FirewallRuleType}.
 *
 * <p>In {@link FirewallMode#AUDIT} both values are recorded and neither blocks;
 * {@link #BLOCK} only takes effect in {@link FirewallMode#QUARANTINE}.
 */
public enum FirewallAction {

    /** Record the violation and let the request through. */
    WARN,

    /** Record the violation and deny the component. */
    BLOCK
}
