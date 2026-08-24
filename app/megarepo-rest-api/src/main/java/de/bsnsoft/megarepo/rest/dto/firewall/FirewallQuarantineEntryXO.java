package de.bsnsoft.megarepo.rest.dto.firewall;

import de.bsnsoft.megarepo.core.firewall.FirewallQuarantineReason;
import de.bsnsoft.megarepo.core.firewall.FirewallQuarantineResolution;
import de.bsnsoft.megarepo.core.firewall.FirewallQuarantineState;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One row of the quarantine queue.
 *
 * <p>Carries enough for an operator to decide without opening anything else: what
 * the component is, why it is held, what the firewall found, and — through
 * {@link #hitCount()} — how much it is costing. A queue that shows only "held
 * since 09:12" makes every entry look equally urgent.
 *
 * @param componentKey the purl, or a {@code sha256:…} digest for content with no
 *     coordinates
 * @param reason which of the three triggers fired
 * @param resolution how it left, or null while still held
 * @param advisoryIds advisory ids from the decision snapshot, hoisted out of
 *     {@link #evaluation()} so the list view does not have to parse JSON
 * @param evaluation the full decision snapshot for the detail view: matched
 *     rules, confidences, sources, the request that tripped it
 * @param hitCount how often a client has asked for the held component
 * @param nextEvaluationAt when the sweep will look again — for a MIN_AGE entry
 *     this is the moment it will be released, which is the single most useful
 *     thing the queue can tell a waiting developer
 */
public record FirewallQuarantineEntryXO(
        UUID id,
        UUID repositoryId,
        String repositoryName,
        String componentKey,
        String path,
        FirewallQuarantineState state,
        FirewallQuarantineReason reason,
        FirewallQuarantineResolution resolution,
        UUID policyId,
        String policyName,
        List<String> advisoryIds,
        Map<String, Object> evaluation,
        Instant firstSeen,
        Instant lastSeen,
        long hitCount,
        Instant lastEvaluatedAt,
        Instant nextEvaluationAt,
        Instant decidedAt,
        String decidedBy,
        String decisionReason,
        UUID exemptionId) {}
