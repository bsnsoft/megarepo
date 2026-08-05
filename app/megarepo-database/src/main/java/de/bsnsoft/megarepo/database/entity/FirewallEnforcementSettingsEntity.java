package de.bsnsoft.megarepo.database.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * The global enforcement switch of the repository firewall — the single source
 * of truth for whether this instance may block a download at all.
 *
 * <p>Singleton row, id {@code 1}, seeded {@code false} by V17. Read it through
 * {@link de.bsnsoft.megarepo.database.repository.FirewallEnforcementSettingsJpaRepository#current()}.
 *
 * <h2>How it combines with the per-repository mode</h2>
 *
 * Two independent facts decide what happens to a download, and both must say yes:
 *
 * <ol>
 *   <li>this flag — the operator has armed the instance;</li>
 *   <li>{@code firewall_repository_config.mode} for the repository —
 *       {@link de.bsnsoft.megarepo.core.firewall.FirewallMode#QUARANTINE}.</li>
 * </ol>
 *
 * <p>Anything else observes and records only. A repository on QUARANTINE while
 * this flag is false is <em>not</em> protected — it is an intent, not a state,
 * and every surface that shows the mode has to say so, or the operator will
 * believe they are covered when they are not. See
 * {@link de.bsnsoft.megarepo.core.firewall.FirewallEffectiveState}, which is
 * where that combination is resolved once so no reader has to redo it.
 *
 * <h2>For the enforcement (read) path</h2>
 *
 * Resolve the flag from this row. Do not introduce a second representation of it
 * — a Spring property, a static field, a copy in {@code firewall_policy} — the
 * failure mode of an enforcement switch with two sources is that one of them
 * says "off" while downloads are being denied. A deployment-side property may
 * still exist as a hard <em>kill</em> switch that can only force enforcement off
 * (subtracting from this row, never adding to it); that stays unambiguous
 * because it can never turn blocking on behind the operator's back.
 *
 * <p>The row is written rarely (an administrator flipping a switch) and read on
 * every evaluated download, so the read path must cache it. Nothing here does
 * that: caching belongs to the enforcement path, which knows how stale it is
 * willing to be.
 */
@Entity
@Table(name = "firewall_enforcement_settings")
public class FirewallEnforcementSettingsEntity {

    /** The only valid id; a CHECK constraint in V17 enforces it. */
    public static final Integer SINGLETON_ID = 1;

    @Id
    @Column(name = "id")
    private Integer id = SINGLETON_ID;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = false;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "updated_by", length = 200)
    private String updatedBy;

    public FirewallEnforcementSettingsEntity() {}

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
}
