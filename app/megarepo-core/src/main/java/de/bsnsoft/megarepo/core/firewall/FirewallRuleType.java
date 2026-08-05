package de.bsnsoft.megarepo.core.firewall;

/**
 * The kinds of policy rule the engine knows about.
 *
 * <p>Persisted as the enum name in {@code firewall_policy_rule.rule_type} and
 * {@code firewall_violation.rule_type}. Neither column carries a CHECK
 * constraint: per design section 3 the per-rule parameters live in the
 * {@code config} JSONB column precisely so that adding a rule type stays a code
 * change and never becomes a migration. Adding a constant here is therefore the
 * whole change — but note that a row written by a newer version is not readable
 * by an older one, which matters only on rollback.
 *
 * <p>{@link #TYPOSQUAT} and {@link #NAMESPACE_CONFUSION} are heuristics and are
 * labelled as such in findings (design section 6).
 */
public enum FirewallRuleType {

    /** Component has an advisory at or above a CVSS score given in {@code config}. */
    CVSS_THRESHOLD,

    /** Component is flagged as malicious by an advisory source (OSV {@code MAL-} ids). */
    KNOWN_MALICIOUS,

    /** Declared license matches a deny/allow list in {@code config}. */
    LICENSE,

    /** Component version was published more recently than a minimum age in {@code config}. */
    MIN_AGE,

    /** No advisory data exists for the component — the "unknown component" fast path. */
    UNKNOWN_COMPONENT,

    /** Heuristic: name is a near-miss of a popular package. */
    TYPOSQUAT,

    /** Heuristic: known name published under an unexpected namespace. */
    NAMESPACE_CONFUSION
}
