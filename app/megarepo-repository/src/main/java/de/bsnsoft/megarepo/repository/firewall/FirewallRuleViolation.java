package de.bsnsoft.megarepo.repository.firewall;

import de.bsnsoft.megarepo.core.firewall.FirewallAction;
import de.bsnsoft.megarepo.core.firewall.FirewallRuleType;

import java.util.List;

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
 * @param ruleType which rule matched
 * @param action what that rule asks for
 * @param reason one sentence, written for a developer reading a build log —
 *     concrete numbers, no policy jargon
 * @param advisoryIds the advisory ids that made the rule match, sorted; empty
 *     for rules that do not derive from advisories
 */
public record FirewallRuleViolation(
        FirewallRuleType ruleType, FirewallAction action, String reason, List<String> advisoryIds) {

    public FirewallRuleViolation {
        advisoryIds = advisoryIds == null ? List.of() : List.copyOf(advisoryIds);
    }

    /** Whether this rule asks for the download to be denied. */
    public boolean blocks() {
        return action == FirewallAction.BLOCK;
    }
}
