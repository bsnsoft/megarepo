package de.bsnsoft.megarepo.core.firewall;

/**
 * Why a component was put into quarantine.
 *
 * <p>Persisted as the enum name in {@code firewall_quarantine.reason_code}. The
 * column deliberately carries <b>no</b> CHECK constraint, for the same reason
 * {@code firewall_policy_rule.rule_type} does not: a rule type that can be added
 * in code must be able to name its own quarantine reason in code too, and a
 * CHECK would turn that into a migration.
 *
 * <p>There are exactly three triggers, and the list is the customer's, not
 * ours. Each one is a verdict that is expected to change without anybody doing
 * anything: the component gets older, the advisory data arrives, the evaluation
 * succeeds on the next attempt. Everything else a policy can decide is a plain
 * block — see {@link FirewallQuarantineState}.
 */
public enum FirewallQuarantineReason {

    /**
     * The {@link FirewallRuleType#MIN_AGE} rule matched: the component version
     * was published more recently than the policy allows.
     *
     * <p>The archetypal quarantine reason. A package uploaded twenty minutes ago
     * is not known-bad, it is unproven — and it stops being unproven by itself,
     * at a time the policy already states. Re-evaluation releases it when the
     * age is reached.
     */
    MIN_AGE_NOT_MET,

    /**
     * The {@link FirewallRuleType#UNKNOWN_COMPONENT} rule matched: no advisory
     * source has anything to say about this component, or it could not be
     * identified as a package at all.
     *
     * <p>Held rather than refused because "we have no data" is a statement about
     * the firewall, not about the component. It resolves when a sync brings data
     * in, or when an operator decides the silence is acceptable.
     */
    UNKNOWN_COMPONENT,

    /**
     * The firewall could not finish evaluating the component — component facts
     * not fetched yet, the advisory lookup timed out, the evaluation pool was
     * saturated — <em>and</em> the repository is
     * {@link FirewallFailMode#FAIL_CLOSED}.
     *
     * <p>Only fail-closed repositories get here. A fail-open repository serves
     * the download instead, which is the whole point of the setting: an
     * unavailable firewall must not break every build.
     */
    EVALUATION_INCOMPLETE,

    /**
     * Not an entry reason: recorded when a re-evaluation of a quarantined entry
     * finds a genuinely blocking violation and moves it to
     * {@link FirewallQuarantineState#BLOCKED}.
     *
     * <p>It exists so the row can say <em>why</em> the answer changed. A
     * component never <em>enters</em> quarantine under this reason — a policy
     * violation found on the request path is refused outright.
     */
    POLICY_VIOLATION;

    /** Whether this reason can put a component into quarantine in the first place. */
    public boolean isEntryReason() {
        return this != POLICY_VIOLATION;
    }
}
