package de.bsnsoft.megarepo.rest.dto.firewall;

import de.bsnsoft.megarepo.core.firewall.FirewallAction;
import de.bsnsoft.megarepo.core.firewall.FirewallRuleType;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One recorded finding — what a policy <em>would</em> have done, or did.
 *
 * <p>This is the evidence the whole page is built around: an operator decides
 * whether to arm the firewall by reading what AUDIT mode has been collecting.
 *
 * @param repositoryId null once the repository has been deleted;
 *     {@link #repositoryName()} still names it (V13)
 * @param action what the matched rule calls for. In AUDIT nothing was withheld
 *     regardless of this value — {@code BLOCK} here means "would have been
 *     blocked", which is exactly the number that makes arming a decision rather
 *     than a leap.
 * @param requestContext who or what triggered the evaluation (user, ip, path,
 *     method), verbatim from the recorded JSONB
 */
public record FirewallViolationXO(
        Long id,
        UUID repositoryId,
        String repositoryName,
        String purl,
        UUID policyId,
        FirewallRuleType ruleType,
        FirewallAction action,
        List<String> advisoryIds,
        Instant occurredAt,
        Map<String, Object> requestContext) {}
