package de.bsnsoft.megarepo.rest.dto.firewall;

import de.bsnsoft.megarepo.core.firewall.FirewallExemptionScope;
import de.bsnsoft.megarepo.core.firewall.FirewallRuleType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Request body for asking for an exemption.
 *
 * <p>Filed either by an administrator from the Exemptions page or by a developer
 * from the link in a firewall 403 — the customer asked for the second, and it is
 * the reason the block body carries a URL at all. Both create the exemption in
 * {@code REQUESTED}; nothing is let through until an approver acts.
 *
 * @param componentKey what to exempt: the purl from the block response
 * @param scope defaults to {@code VERSION} when omitted — the version in front of
 *     the requester is the one they can vouch for. {@code COMPONENT} covers
 *     versions that do not exist yet, which is a decision an approver should make
 *     consciously
 * @param repositoryId the repository, or null for all of them
 * @param ruleType the rule that fired, or null for every rule. The block page
 *     pre-fills the rule that actually fired, because a narrow request is one an
 *     approver can say yes to
 * @param advisoryIds specific advisories to exempt, or empty for all
 * @param requestedExpiry the requester's suggestion; the approver decides
 * @param justification why. Required — an unexplained exemption is a V8 whitelist
 *     entry with more columns
 */
public record FirewallExemptionRequestXO(
        @NotBlank @Size(max = 1000) String componentKey,
        FirewallExemptionScope scope,
        UUID repositoryId,
        FirewallRuleType ruleType,
        List<String> advisoryIds,
        Instant requestedExpiry,
        @NotBlank @Size(max = 2000) String justification) {}
