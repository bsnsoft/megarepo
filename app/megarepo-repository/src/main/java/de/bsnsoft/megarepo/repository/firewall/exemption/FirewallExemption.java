package de.bsnsoft.megarepo.repository.firewall.exemption;

import de.bsnsoft.megarepo.core.firewall.FirewallComponentKeyKind;
import de.bsnsoft.megarepo.core.firewall.FirewallExemptionScope;
import de.bsnsoft.megarepo.core.firewall.FirewallExemptionState;
import de.bsnsoft.megarepo.core.firewall.FirewallRuleType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A decision to let a component past the firewall despite a policy.
 *
 * <p>The word is <b>exemption</b> throughout — entity, REST path, UI label. Not
 * "waiver", not "whitelist". The customer settled that and it is not a matter of
 * taste: "whitelist" is what the V8 feature was called, and the V8 feature is
 * precisely the thing whose behaviour nobody could reconstruct a year later.
 *
 * @param id the exemption
 * @param componentKey what is exempted, in the scheme {@link #keyKind} names
 * @param keyKind {@link FirewallComponentKeyKind#PURL} for anything created by
 *     Phase 2, {@code LEGACY_COORDINATE} for a row carried over from the V8
 *     whitelist by migration V18
 * @param scope this version, or every version
 * @param repositoryId the repository it applies to, or null for all of them
 * @param ruleType the single rule it exempts from, or null for every rule
 * @param advisoryIds the advisories it covers, or empty for all of them
 * @param state where it stands; only {@link FirewallExemptionState#APPROVED} lets
 *     anything through
 * @param expiresAt when it lapses, or null for never. A null expiry is a
 *     deliberate choice an approver has to make, not a default
 * @param expiryNotifiedAt when the "lapses soon" notice went out, so it goes out
 *     once
 * @param justification why it was asked for. Required — an exemption without a
 *     stated reason is the V8 whitelist with extra columns
 * @param requestedBy who asked
 * @param requestedAt when
 * @param approvedBy who signed it off, or null
 * @param approvedAt when
 * @param decisionNote what the approver said
 */
public record FirewallExemption(
        UUID id,
        String componentKey,
        FirewallComponentKeyKind keyKind,
        FirewallExemptionScope scope,
        UUID repositoryId,
        FirewallRuleType ruleType,
        List<String> advisoryIds,
        FirewallExemptionState state,
        Instant expiresAt,
        Instant expiryNotifiedAt,
        String justification,
        String requestedBy,
        Instant requestedAt,
        String approvedBy,
        Instant approvedAt,
        String decisionNote) {

    public FirewallExemption {
        keyKind = keyKind == null ? FirewallComponentKeyKind.PURL : keyKind;
        scope = scope == null ? FirewallExemptionScope.VERSION : scope;
        state = state == null ? FirewallExemptionState.REQUESTED : state;
        advisoryIds = advisoryIds == null ? List.of() : List.copyOf(advisoryIds);
    }

    /**
     * Whether this exemption suppresses violations at the given instant.
     *
     * <p>Checks the expiry as well as the state on purpose. The expiry task runs
     * daily and flips lapsed rows to {@code EXPIRED}; an exemption that expired at
     * noon has to stop applying at noon, not at the next sweep. An expired
     * exemption blocks again — that is the point of giving exemptions an expiry,
     * and the behaviour the V8 whitelist could not express.
     */
    public boolean isLiveAt(Instant when) {
        if (!state.grantsPassage()) {
            return false;
        }
        return expiresAt == null || when == null || expiresAt.isAfter(when);
    }

    /** Whether it covers the given rule. */
    public boolean covers(FirewallRuleType candidate) {
        return ruleType == null || ruleType == candidate;
    }

    /** Whether it covers the given advisory id. */
    public boolean coversAdvisory(String advisoryId) {
        return advisoryIds.isEmpty() || advisoryIds.contains(advisoryId);
    }

    /** Whether it applies in the given repository. */
    public boolean appliesIn(UUID candidate) {
        return repositoryId == null || repositoryId.equals(candidate);
    }

    /** Whether it never lapses. */
    public boolean isPermanent() {
        return expiresAt == null;
    }
}
