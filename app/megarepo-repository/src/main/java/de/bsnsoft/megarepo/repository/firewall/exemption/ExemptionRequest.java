package de.bsnsoft.megarepo.repository.firewall.exemption;

import de.bsnsoft.megarepo.core.firewall.FirewallExemptionScope;
import de.bsnsoft.megarepo.core.firewall.FirewallRuleType;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A request for an exemption, as it arrives from the block page or the admin
 * API.
 *
 * <h2>The developer-facing half of a 403</h2>
 *
 * The customer's requirement is that a blocked developer can ask for an
 * exemption from the block page rather than opening a ticket, and that the block
 * response carries the link that starts it. Everything in this record is
 * therefore either already known to the block page (the component, the
 * repository, the rule that fired) or is the one thing the developer has to
 * type: {@link #justification}.
 *
 * <p>An exemption is created {@code REQUESTED}. It changes nothing until an
 * approver acts — a request that took effect on submission would be a
 * self-service bypass of the firewall, which is not an exemption workflow but the
 * absence of one.
 *
 * @param componentKey what to exempt: {@code ComponentIdentity.key()}. Always a
 *     purl or content digest — a request never creates a legacy coordinate,
 *     which only migration V18 writes
 * @param scope this version, or every version. Defaults to
 *     {@link FirewallExemptionScope#VERSION}: the version in front of the
 *     developer is the one they can vouch for
 * @param repositoryId the repository, or null for all of them
 * @param ruleType the rule to exempt from, or null for every rule. The block page
 *     fills in the rule that actually fired, which is narrower and easier to
 *     approve than a blanket pass
 * @param advisoryIds the advisories to exempt, or empty for all
 * @param requestedExpiry when the requester suggests it should lapse, or null.
 *     A suggestion — the approver decides
 * @param justification why. Required and non-blank
 * @param requestedBy who is asking
 */
public record ExemptionRequest(
        String componentKey,
        FirewallExemptionScope scope,
        UUID repositoryId,
        FirewallRuleType ruleType,
        List<String> advisoryIds,
        Instant requestedExpiry,
        String justification,
        String requestedBy) {

    public ExemptionRequest {
        Objects.requireNonNull(componentKey, "componentKey must not be null");
        if (justification == null || justification.isBlank()) {
            throw new IllegalArgumentException(
                    "an exemption needs a justification — an unexplained one is a whitelist entry");
        }
        if (requestedBy == null || requestedBy.isBlank()) {
            throw new IllegalArgumentException("requestedBy must name a user");
        }
        scope = scope == null ? FirewallExemptionScope.VERSION : scope;
        advisoryIds = advisoryIds == null ? List.of() : List.copyOf(advisoryIds);
    }
}
