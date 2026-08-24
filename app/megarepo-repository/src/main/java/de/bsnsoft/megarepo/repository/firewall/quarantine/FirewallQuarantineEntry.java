package de.bsnsoft.megarepo.repository.firewall.quarantine;

import de.bsnsoft.megarepo.core.firewall.FirewallQuarantineReason;
import de.bsnsoft.megarepo.core.firewall.FirewallQuarantineResolution;
import de.bsnsoft.megarepo.core.firewall.FirewallQuarantineState;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One component held in one repository, as everything outside the persistence
 * layer sees it.
 *
 * <p>A value type rather than {@code FirewallQuarantineEntity} because this
 * crosses into the REST layer and into the request path: an entity returned from
 * a transactional service is a lazy-loading trap and an accidental write path,
 * and the quarantine queue is one of the few screens where a stale or
 * half-initialised row would be read as an operational fact.
 *
 * @param id the entry
 * @param repositoryId the repository holding it — through a group always the
 *     member that resolved the artifact, never the group
 * @param repositoryName its name
 * @param componentKey {@code ComponentIdentity.key()}: a canonical purl, or a
 *     {@code sha256:…} digest for content with no coordinates
 * @param path an artifact path the component was requested under, for context
 * @param state where the entry stands
 * @param reason why it was held
 * @param resolution how it left, or null while still held
 * @param policyId the policy that decided, or null
 * @param evaluation snapshot of the decision — matched rules, advisory ids,
 *     confidences, the request that tripped it
 * @param firstSeen when it was first held
 * @param lastSeen when a client last asked for it
 * @param hitCount how often it has been asked for. The number that tells an
 *     operator whether this is blocking one nightly job or the whole CI fleet
 * @param lastEvaluatedAt when the re-evaluation last looked
 * @param nextEvaluationAt when it should look again — for a MIN_AGE entry, the
 *     exact moment the component becomes old enough
 * @param decidedAt when it was released or blocked
 * @param decidedBy who decided: a user name, or {@code system} for the sweep
 * @param decisionReason the human sentence beside the machine-readable
 *     {@link #resolution}
 * @param exemptionId the exemption that released it, when one did
 */
public record FirewallQuarantineEntry(
        UUID id,
        UUID repositoryId,
        String repositoryName,
        String componentKey,
        String path,
        FirewallQuarantineState state,
        FirewallQuarantineReason reason,
        FirewallQuarantineResolution resolution,
        UUID policyId,
        Map<String, Object> evaluation,
        Instant firstSeen,
        Instant lastSeen,
        long hitCount,
        Instant lastEvaluatedAt,
        Instant nextEvaluationAt,
        Instant decidedAt,
        String decidedBy,
        String decisionReason,
        UUID exemptionId) {

    public FirewallQuarantineEntry {
        state = state == null ? FirewallQuarantineState.QUARANTINED : state;
        evaluation = evaluation == null ? Map.of() : Map.copyOf(evaluation);
    }

    /** Whether a download of this component must be refused right now. */
    public boolean denies() {
        return state.denies();
    }

    /** Whether the sweep should still be looking at this entry. */
    public boolean held() {
        return state == FirewallQuarantineState.QUARANTINED;
    }
}
