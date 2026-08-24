package de.bsnsoft.megarepo.database.entity;

import de.bsnsoft.megarepo.core.firewall.FirewallComponentKeyKind;
import de.bsnsoft.megarepo.core.firewall.FirewallExemptionScope;
import de.bsnsoft.megarepo.core.firewall.FirewallExemptionState;
import de.bsnsoft.megarepo.core.firewall.FirewallRuleType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * A decision to let a component past the firewall despite a policy, mapped onto
 * {@code firewall_exemption} (V17).
 *
 * <p>The V8 whitelist this replaces had a value, a reason and an author. This
 * has an expiry, a requester, an approver, an explicit scope and a state — the
 * five things that turn "somebody once allowed this" into something an auditor
 * can read a year later.
 *
 * <p>Only {@link FirewallExemptionState#APPROVED} with an unreached
 * {@code expiresAt} suppresses anything. Expiry is a stored transition performed
 * by the expiry task, not something derived at read time; see V19.
 */
@Entity
@Table(name = "firewall_exemption")
public class FirewallExemptionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(name = "id")
    private UUID id;

    /**
     * What is exempted: a canonical purl, a {@code sha256:…} content identity, or
     * — for rows migrated from the V8 whitelist by V18 — a legacy
     * {@code format:namespace:name[:version]} coordinate. {@link #keyKind} says
     * which.
     */
    @Column(name = "component_key", nullable = false, length = 1000)
    private String componentKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "key_kind", nullable = false, length = 20)
    private FirewallComponentKeyKind keyKind = FirewallComponentKeyKind.PURL;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false, length = 20)
    private FirewallExemptionScope scopeType = FirewallExemptionScope.VERSION;

    /** Null means every repository — the V8 whitelist's only behaviour. */
    @Column(name = "repository_id")
    private UUID repositoryId;

    /** Null means every rule. */
    @Enumerated(EnumType.STRING)
    @Column(name = "rule_type", length = 40)
    private FirewallRuleType ruleType;

    /** Empty means every advisory. */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "advisory_ids", nullable = false, columnDefinition = "text[]")
    private String[] advisoryIds = new String[0];

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 20)
    private FirewallExemptionState state = FirewallExemptionState.REQUESTED;

    /** Null means it never expires. */
    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "expiry_notified_at")
    private Instant expiryNotifiedAt;

    @Column(name = "justification", nullable = false, length = 2000)
    private String justification;

    @Column(name = "requested_by", nullable = false, length = 200)
    private String requestedBy;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt = Instant.now();

    @Column(name = "approved_by", length = 200)
    private String approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "decision_note", length = 1000)
    private String decisionNote;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public FirewallExemptionEntity() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getComponentKey() { return componentKey; }
    public void setComponentKey(String componentKey) { this.componentKey = componentKey; }
    public FirewallComponentKeyKind getKeyKind() { return keyKind; }
    public void setKeyKind(FirewallComponentKeyKind keyKind) { this.keyKind = keyKind; }
    public FirewallExemptionScope getScopeType() { return scopeType; }
    public void setScopeType(FirewallExemptionScope scopeType) { this.scopeType = scopeType; }
    public UUID getRepositoryId() { return repositoryId; }
    public void setRepositoryId(UUID repositoryId) { this.repositoryId = repositoryId; }
    public FirewallRuleType getRuleType() { return ruleType; }
    public void setRuleType(FirewallRuleType ruleType) { this.ruleType = ruleType; }
    public String[] getAdvisoryIds() { return advisoryIds; }
    public void setAdvisoryIds(String[] advisoryIds) { this.advisoryIds = advisoryIds; }
    public FirewallExemptionState getState() { return state; }
    public void setState(FirewallExemptionState state) { this.state = state; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Instant getExpiryNotifiedAt() { return expiryNotifiedAt; }
    public void setExpiryNotifiedAt(Instant expiryNotifiedAt) { this.expiryNotifiedAt = expiryNotifiedAt; }
    public String getJustification() { return justification; }
    public void setJustification(String justification) { this.justification = justification; }
    public String getRequestedBy() { return requestedBy; }
    public void setRequestedBy(String requestedBy) { this.requestedBy = requestedBy; }
    public Instant getRequestedAt() { return requestedAt; }
    public void setRequestedAt(Instant requestedAt) { this.requestedAt = requestedAt; }
    public String getApprovedBy() { return approvedBy; }
    public void setApprovedBy(String approvedBy) { this.approvedBy = approvedBy; }
    public Instant getApprovedAt() { return approvedAt; }
    public void setApprovedAt(Instant approvedAt) { this.approvedAt = approvedAt; }
    public String getDecisionNote() { return decisionNote; }
    public void setDecisionNote(String decisionNote) { this.decisionNote = decisionNote; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
