package de.bsnsoft.megarepo.rest.dto.firewall;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A named policy and its rules.
 *
 * @param isDefault whether this is the global default. At most one policy can be
 *     — a partial unique index in V11 says so in the schema rather than leaving
 *     it to application code
 * @param assignedRepositories how many repositories point at this policy.
 *     Included so the policy list can warn before an edit, and so deleting a
 *     policy that eleven repositories rely on is visibly not a tidy-up
 * @param enforcingRepositories how many of those are actually in QUARANTINE mode.
 *     The number that says whether editing this policy can break a build right
 *     now; the rest are only observing
 */
public record FirewallPolicyXO(
        UUID id,
        String name,
        String description,
        boolean isDefault,
        List<FirewallPolicyRuleXO> rules,
        int assignedRepositories,
        int enforcingRepositories,
        Instant createdAt,
        String createdBy) {}
