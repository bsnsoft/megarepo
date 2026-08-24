package de.bsnsoft.megarepo.database.entity;

import de.bsnsoft.megarepo.core.firewall.FirewallFailMode;
import de.bsnsoft.megarepo.core.firewall.FirewallMode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Per-repository firewall configuration. The primary key <em>is</em> the
 * repository id — one row per repository at most, no surrogate key.
 */
@Entity
@Table(name = "firewall_repository_config")
public class FirewallRepositoryConfigEntity {

    @Id
    @Column(name = "repository_id")
    private UUID repositoryId;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 20)
    private FirewallMode mode = FirewallMode.AUDIT;

    @Column(name = "policy_id")
    private UUID policyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "fail_mode", nullable = false, length = 20)
    private FirewallFailMode failMode = FirewallFailMode.FAIL_OPEN;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public FirewallRepositoryConfigEntity() {}

    public UUID getRepositoryId() { return repositoryId; }
    public void setRepositoryId(UUID repositoryId) { this.repositoryId = repositoryId; }
    public FirewallMode getMode() { return mode; }
    public void setMode(FirewallMode mode) { this.mode = mode; }
    public UUID getPolicyId() { return policyId; }
    public void setPolicyId(UUID policyId) { this.policyId = policyId; }
    public FirewallFailMode getFailMode() { return failMode; }
    public void setFailMode(FirewallFailMode failMode) { this.failMode = failMode; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
