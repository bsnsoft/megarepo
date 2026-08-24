package de.bsnsoft.megarepo.database.entity;

import de.bsnsoft.megarepo.core.firewall.FirewallQuarantineReason;
import de.bsnsoft.megarepo.core.firewall.FirewallQuarantineResolution;
import de.bsnsoft.megarepo.core.firewall.FirewallQuarantineState;
import de.bsnsoft.megarepo.database.converter.JsonbConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One component held in one repository, mapped onto {@code firewall_quarantine}
 * (V17).
 *
 * <p>Not every denied download produces a row here. Quarantine is for verdicts
 * that are expected to change on their own — the component is too new, nothing
 * is known about it yet, or the evaluation did not finish and the repository is
 * fail-closed. A critical advisory or a malicious package is refused outright.
 * See {@link FirewallQuarantineState}.
 *
 * <p>The natural key is {@code (repositoryId, componentKey)}, enforced by a
 * unique constraint: one decision per component, not one per path it happens to
 * be reachable under.
 */
@Entity
@Table(name = "firewall_quarantine")
public class FirewallQuarantineEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(name = "id")
    private UUID id;

    @Column(name = "repository_id", nullable = false)
    private UUID repositoryId;

    @Column(name = "repository_name", nullable = false, length = 200)
    private String repositoryName;

    /** {@code ComponentIdentity.key()} — a canonical purl or a {@code sha256:…} digest. */
    @Column(name = "component_key", nullable = false, length = 1000)
    private String componentKey;

    @Column(name = "component_id")
    private UUID componentId;

    @Column(name = "path", length = 2048)
    private String path;

    @Column(name = "asset_sha256", length = 64)
    private String assetSha256;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 20)
    private FirewallQuarantineState state = FirewallQuarantineState.QUARANTINED;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code", nullable = false, length = 40)
    private FirewallQuarantineReason reasonCode;

    /** Null while the entry is still held. */
    @Enumerated(EnumType.STRING)
    @Column(name = "resolution", length = 40)
    private FirewallQuarantineResolution resolution;

    @Column(name = "policy_id")
    private UUID policyId;

    /** Snapshot of the decision that created the entry: rules, advisories, request. */
    @Convert(converter = JsonbConverter.class)
    @Column(name = "evaluation", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> evaluation = new HashMap<>();

    @Column(name = "first_seen", nullable = false)
    private Instant firstSeen = Instant.now();

    @Column(name = "last_seen", nullable = false)
    private Instant lastSeen = Instant.now();

    /** How often a client has asked for the held component. Operator signal, not state. */
    @Column(name = "hit_count", nullable = false)
    private long hitCount = 1;

    @Column(name = "last_evaluated_at")
    private Instant lastEvaluatedAt;

    /**
     * When the sweep should look at this entry again. Lets a MIN_AGE entry be
     * scheduled for the exact moment it becomes old enough instead of being
     * re-evaluated every quarter of an hour until then.
     */
    @Column(name = "next_evaluation_at")
    private Instant nextEvaluationAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    /** Who decided — a user name, or {@code system} for the scheduled sweep. */
    @Column(name = "decided_by", length = 200)
    private String decidedBy;

    @Column(name = "decision_reason", length = 1000)
    private String decisionReason;

    @Column(name = "exemption_id")
    private UUID exemptionId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public FirewallQuarantineEntity() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getRepositoryId() { return repositoryId; }
    public void setRepositoryId(UUID repositoryId) { this.repositoryId = repositoryId; }
    public String getRepositoryName() { return repositoryName; }
    public void setRepositoryName(String repositoryName) { this.repositoryName = repositoryName; }
    public String getComponentKey() { return componentKey; }
    public void setComponentKey(String componentKey) { this.componentKey = componentKey; }
    public UUID getComponentId() { return componentId; }
    public void setComponentId(UUID componentId) { this.componentId = componentId; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public String getAssetSha256() { return assetSha256; }
    public void setAssetSha256(String assetSha256) { this.assetSha256 = assetSha256; }
    public FirewallQuarantineState getState() { return state; }
    public void setState(FirewallQuarantineState state) { this.state = state; }
    public FirewallQuarantineReason getReasonCode() { return reasonCode; }
    public void setReasonCode(FirewallQuarantineReason reasonCode) { this.reasonCode = reasonCode; }
    public FirewallQuarantineResolution getResolution() { return resolution; }
    public void setResolution(FirewallQuarantineResolution resolution) { this.resolution = resolution; }
    public UUID getPolicyId() { return policyId; }
    public void setPolicyId(UUID policyId) { this.policyId = policyId; }
    public Map<String, Object> getEvaluation() { return evaluation; }
    public void setEvaluation(Map<String, Object> evaluation) { this.evaluation = evaluation; }
    public Instant getFirstSeen() { return firstSeen; }
    public void setFirstSeen(Instant firstSeen) { this.firstSeen = firstSeen; }
    public Instant getLastSeen() { return lastSeen; }
    public void setLastSeen(Instant lastSeen) { this.lastSeen = lastSeen; }
    public long getHitCount() { return hitCount; }
    public void setHitCount(long hitCount) { this.hitCount = hitCount; }
    public Instant getLastEvaluatedAt() { return lastEvaluatedAt; }
    public void setLastEvaluatedAt(Instant lastEvaluatedAt) { this.lastEvaluatedAt = lastEvaluatedAt; }
    public Instant getNextEvaluationAt() { return nextEvaluationAt; }
    public void setNextEvaluationAt(Instant nextEvaluationAt) { this.nextEvaluationAt = nextEvaluationAt; }
    public Instant getDecidedAt() { return decidedAt; }
    public void setDecidedAt(Instant decidedAt) { this.decidedAt = decidedAt; }
    public String getDecidedBy() { return decidedBy; }
    public void setDecidedBy(String decidedBy) { this.decidedBy = decidedBy; }
    public String getDecisionReason() { return decisionReason; }
    public void setDecisionReason(String decisionReason) { this.decisionReason = decisionReason; }
    public UUID getExemptionId() { return exemptionId; }
    public void setExemptionId(UUID exemptionId) { this.exemptionId = exemptionId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
