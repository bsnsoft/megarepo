package de.bsnsoft.megarepo.database.entity;

import de.bsnsoft.megarepo.core.firewall.FirewallAction;
import de.bsnsoft.megarepo.core.firewall.FirewallRuleType;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A recorded policy violation. In AUDIT mode this is the only thing the
 * firewall writes: what a policy would have done, without doing it.
 *
 * <p>Append-only log, so the key is a {@code BIGSERIAL} like {@link AuditLogEntity}
 * and {@link NvdFirewallBlockEntity} rather than a UUID.
 */
@Entity
@Table(name = "firewall_violation")
public class FirewallViolationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** Null once the repository has been deleted; {@link #repositoryName} survives. */
    @Column(name = "repository_id")
    private UUID repositoryId;

    @Column(name = "repository_name", nullable = false, length = 200)
    private String repositoryName;

    @Column(name = "purl", nullable = false, length = 1000)
    private String purl;

    @Column(name = "policy_id")
    private UUID policyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "rule_type", nullable = false, length = 40)
    private FirewallRuleType ruleType;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 10)
    private FirewallAction action;

    /** Advisory ids that triggered the rule; empty for rules that need none. */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "advisory_ids", nullable = false, columnDefinition = "text[]")
    private String[] advisoryIds = new String[0];

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt = Instant.now();

    /** Who/what triggered the evaluation: user, ip, path, request method. */
    @Convert(converter = JsonbConverter.class)
    @Column(name = "request_context", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> requestContext = new HashMap<>();

    public FirewallViolationEntity() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public UUID getRepositoryId() { return repositoryId; }
    public void setRepositoryId(UUID repositoryId) { this.repositoryId = repositoryId; }
    public String getRepositoryName() { return repositoryName; }
    public void setRepositoryName(String repositoryName) { this.repositoryName = repositoryName; }
    public String getPurl() { return purl; }
    public void setPurl(String purl) { this.purl = purl; }
    public UUID getPolicyId() { return policyId; }
    public void setPolicyId(UUID policyId) { this.policyId = policyId; }
    public FirewallRuleType getRuleType() { return ruleType; }
    public void setRuleType(FirewallRuleType ruleType) { this.ruleType = ruleType; }
    public FirewallAction getAction() { return action; }
    public void setAction(FirewallAction action) { this.action = action; }
    public String[] getAdvisoryIds() { return advisoryIds; }
    public void setAdvisoryIds(String[] advisoryIds) { this.advisoryIds = advisoryIds; }
    public Instant getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Instant occurredAt) { this.occurredAt = occurredAt; }
    public Map<String, Object> getRequestContext() { return requestContext; }
    public void setRequestContext(Map<String, Object> requestContext) { this.requestContext = requestContext; }
}
