package de.bsnsoft.megarepo.rest.dto.firewall;

import de.bsnsoft.megarepo.core.firewall.FirewallComponentKeyKind;
import de.bsnsoft.megarepo.core.firewall.FirewallExemptionScope;
import de.bsnsoft.megarepo.core.firewall.FirewallExemptionState;
import de.bsnsoft.megarepo.core.firewall.FirewallRuleType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One exemption, as the API returns it.
 *
 * <p>Served from {@code /api/v1/firewall/exemptions}. The path, the field names
 * and the UI label all say <b>exemption</b>; "waiver" and "whitelist" appear
 * nowhere in this codebase.
 *
 * @param repositoryName resolved for display; null when the exemption is global,
 *     which the UI renders as "all repositories" rather than as a blank cell
 * @param keyKind {@link FirewallComponentKeyKind#LEGACY_COORDINATE} marks a row
 *     carried over from the V8 whitelist. Surfaced rather than hidden: those rows
 *     match by a legacy comparison and an operator should be able to see which of
 *     their exemptions are still on the old scheme
 * @param expiresAt null means it never lapses
 * @param expired whether it has lapsed as of the moment this response was built.
 *     Computed rather than left to the client, because the expiry sweep runs
 *     daily and a list that showed a lapsed exemption as active would be exactly
 *     wrong for up to a day
 */
public record FirewallExemptionXO(
        UUID id,
        String componentKey,
        FirewallComponentKeyKind keyKind,
        FirewallExemptionScope scope,
        UUID repositoryId,
        String repositoryName,
        FirewallRuleType ruleType,
        List<String> advisoryIds,
        FirewallExemptionState state,
        Instant expiresAt,
        boolean expired,
        Instant expiryNotifiedAt,
        String justification,
        String requestedBy,
        Instant requestedAt,
        String approvedBy,
        Instant approvedAt,
        String decisionNote) {}
