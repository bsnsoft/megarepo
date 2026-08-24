package de.bsnsoft.megarepo.repository.firewall.exemption;

import de.bsnsoft.megarepo.core.firewall.FirewallExemptionState;

import java.util.UUID;

/**
 * Filters for the exemption list.
 *
 * <p>All fields nullable and meaning "do not filter on this".
 *
 * @param state only exemptions in this state; null for all
 * @param repositoryId only exemptions scoped to this repository. Note this is an
 *     exact match on the column, so it does <em>not</em> include the global
 *     (null-repository) exemptions that also apply there — a management list and
 *     an applicability check are different questions, and conflating them is how
 *     an operator deletes an exemption they thought was local
 * @param componentKeyContains substring match for the search box; null or blank
 *     for no search
 * @param expiringOnly only exemptions that have an expiry date at all
 */
public record ExemptionQuery(
        FirewallExemptionState state,
        UUID repositoryId,
        String componentKeyContains,
        boolean expiringOnly) {

    /** Everything, unfiltered. */
    public static ExemptionQuery all() {
        return new ExemptionQuery(null, null, null, false);
    }

    /** The approval queue. */
    public static ExemptionQuery pending() {
        return new ExemptionQuery(FirewallExemptionState.REQUESTED, null, null, false);
    }

    /** What is currently letting something through. */
    public static ExemptionQuery approved() {
        return new ExemptionQuery(FirewallExemptionState.APPROVED, null, null, false);
    }
}
