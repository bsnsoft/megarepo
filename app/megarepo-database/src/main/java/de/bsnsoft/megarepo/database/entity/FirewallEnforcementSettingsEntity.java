package de.bsnsoft.megarepo.database.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * The global firewall enforcement switch — one row, id 1.
 *
 * <p>Everything the repository firewall may deny goes through this flag. While
 * {@link #isEnabled()} is false the firewall records but never blocks, whatever
 * a single repository's {@code firewall_repository_config.mode} says. That is
 * deliberate: a per-repository mode is one careless edit away from breaking
 * every build against that repository, and this switch is the thing an operator
 * can turn off in one step when that happens.
 *
 * @see de.bsnsoft.megarepo.database.entity.OutboundProxySettingsEntity the same
 *     singleton-settings shape, including the {@code configured} flag that
 *     decides whether the row or the deployment-side property wins
 */
@Entity
@Table(name = "firewall_enforcement_settings")
public class FirewallEnforcementSettingsEntity {

    @Id
    @Column(name = "id")
    private Integer id = 1;

    /**
     * Whether an operator has ever written this row. False means the
     * deployment-side {@code megarepo.firewall.enforcement.enabled} property is
     * still authoritative.
     */
    @Column(name = "configured", nullable = false)
    private boolean configured = false;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = false;

    /**
     * When enforcement was first switched on. Components whose stored asset is
     * older than this are audited but never blocked — they were already in the
     * repository when the operator flipped the switch.
     *
     * <p>Null until enforcement is first enabled, and a null watermark blocks
     * nothing at all: with no point of reference every component counts as
     * pre-existing, which is the safe direction to fail in.
     */
    @Column(name = "enforcing_since")
    private Instant enforcingSince;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "updated_by", length = 200)
    private String updatedBy;

    public FirewallEnforcementSettingsEntity() {}

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public boolean isConfigured() { return configured; }
    public void setConfigured(boolean configured) { this.configured = configured; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Instant getEnforcingSince() { return enforcingSince; }
    public void setEnforcingSince(Instant enforcingSince) { this.enforcingSince = enforcingSince; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
}
