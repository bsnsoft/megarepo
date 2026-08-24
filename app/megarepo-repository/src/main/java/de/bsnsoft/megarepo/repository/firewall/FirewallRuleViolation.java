package de.bsnsoft.megarepo.repository.firewall;

import de.bsnsoft.megarepo.core.firewall.FirewallAction;
import de.bsnsoft.megarepo.core.firewall.FirewallRuleType;

import java.util.List;
import java.util.UUID;

/**
 * One policy rule that matched a component, and what it asked for.
 *
 * <p>{@link #action()} is what the <em>rule</em> says, not what happened: a
 * {@link FirewallAction#BLOCK} rule that matched a component which was already
 * in the repository still produces a violation with action {@code BLOCK}, and
 * the decision around it records that the download went out anyway. Keeping the
 * two apart is what lets the audit trail answer "what would this policy have
 * done?" — the question the whole observation phase was built to answer.
 *
 * <p>{@link #exemptionId()} is the second way a matched rule can fail to deny
 * anything: an approved exemption covers it. The violation is still produced and
 * still recorded — an exemption is a decision to accept a finding, not a reason
 * to stop noticing it — and the id of the exemption that did it travels with the
 * violation into {@code firewall_violation}. Without that, the log would show a
 * BLOCK rule that matched and a download that went out, with nothing connecting
 * the two.
 *
 * @param ruleType which rule matched
 * @param action what that rule asks for
 * @param reason one sentence, written for a developer reading a build log —
 *     concrete numbers, no policy jargon
 * @param advisoryIds the advisory ids that made the rule match, sorted; empty
 *     for rules that do not derive from advisories
 * @param exemptionId the exemption that suppressed this violation, or null when
 *     none did. Only the engine sets it; a rule never knows about exemptions
 */
public record FirewallRuleViolation(
        FirewallRuleType ruleType,
        FirewallAction action,
        String reason,
        List<String> advisoryIds,
        UUID exemptionId) {

    public FirewallRuleViolation {
        advisoryIds = advisoryIds == null ? List.of() : List.copyOf(advisoryIds);
    }

    /**
     * A violation as a rule produces one: matched, not yet weighed against any
     * exemption.
     */
    public FirewallRuleViolation(
            FirewallRuleType ruleType, FirewallAction action, String reason, List<String> advisoryIds) {
        this(ruleType, action, reason, advisoryIds, null);
    }

    /** Whether this rule asks for the download to be denied. */
    public boolean blocks() {
        return action == FirewallAction.BLOCK;
    }

    /** Whether an approved exemption covers this violation. */
    public boolean exempted() {
        return exemptionId != null;
    }

    /**
     * Whether this violation actually withholds the artifact — it asks to block
     * <em>and</em> nothing exempts it.
     *
     * <p>Separate from {@link #blocks()} so that "the rule wanted to block" and
     * "the download was refused" stay two different statements, which is the same
     * distinction {@link #action()} already makes against a pre-existing
     * component.
     */
    public boolean denies() {
        return blocks() && !exempted();
    }

    /** The same violation, marked as covered by an exemption. */
    public FirewallRuleViolation exemptedBy(UUID exemption) {
        return new FirewallRuleViolation(ruleType, action, reason, advisoryIds, exemption);
    }
}
