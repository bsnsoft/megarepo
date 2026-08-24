package de.bsnsoft.megarepo.database.repository;

import de.bsnsoft.megarepo.core.firewall.FirewallComponentKeyKind;
import de.bsnsoft.megarepo.core.firewall.FirewallExemptionState;
import de.bsnsoft.megarepo.database.entity.FirewallExemptionEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface FirewallExemptionJpaRepository extends JpaRepository<FirewallExemptionEntity, UUID> {

    /**
     * Every live exemption that could apply to one of the given component keys,
     * in this repository or globally.
     *
     * <p>The caller passes the keys it wants matched — for a purl identity that
     * is the full key plus its version-less form, and additionally the V8 legacy
     * coordinate and its version-less prefix while migrated rows still exist. Key
     * building is format knowledge and belongs in the service, not in a query.
     *
     * <p>Expiry is filtered here as well as flipped by the expiry task, on
     * purpose: the task runs daily, and an exemption that expired at noon must
     * stop applying at noon rather than at the next sweep.
     */
    @Query("""
            SELECT e FROM FirewallExemptionEntity e
            WHERE e.state = de.bsnsoft.megarepo.core.firewall.FirewallExemptionState.APPROVED
              AND e.componentKey IN :componentKeys
              AND (e.repositoryId IS NULL OR e.repositoryId = :repositoryId)
              AND (e.expiresAt IS NULL OR e.expiresAt > :now)
            """)
    List<FirewallExemptionEntity> findApplicable(
            @Param("componentKeys") Collection<String> componentKeys,
            @Param("repositoryId") UUID repositoryId,
            @Param("now") Instant now);

    Page<FirewallExemptionEntity> findByStateOrderByRequestedAtDesc(
            FirewallExemptionState state, Pageable pageable);

    Page<FirewallExemptionEntity> findAllByOrderByRequestedAtDesc(Pageable pageable);

    Page<FirewallExemptionEntity> findByRepositoryIdOrderByRequestedAtDesc(
            UUID repositoryId, Pageable pageable);

    List<FirewallExemptionEntity> findByComponentKeyAndState(
            String componentKey, FirewallExemptionState state);

    long countByState(FirewallExemptionState state);

    /** The expiry sweep's work list: approved exemptions whose end date has passed. */
    @Query("""
            SELECT e FROM FirewallExemptionEntity e
            WHERE e.state = de.bsnsoft.megarepo.core.firewall.FirewallExemptionState.APPROVED
              AND e.expiresAt IS NOT NULL
              AND e.expiresAt <= :now
            """)
    List<FirewallExemptionEntity> findExpired(@Param("now") Instant now);

    /**
     * Approved exemptions that lapse within the notice window and have not been
     * announced yet.
     *
     * <p>{@code expiryNotifiedAt IS NULL} is what makes the notice fire once
     * rather than on every sweep between the notice and the lapse.
     */
    @Query("""
            SELECT e FROM FirewallExemptionEntity e
            WHERE e.state = de.bsnsoft.megarepo.core.firewall.FirewallExemptionState.APPROVED
              AND e.expiresAt IS NOT NULL
              AND e.expiresAt > :now
              AND e.expiresAt <= :until
              AND e.expiryNotifiedAt IS NULL
            ORDER BY e.expiresAt ASC
            """)
    List<FirewallExemptionEntity> findDueForExpiryNotice(
            @Param("now") Instant now, @Param("until") Instant until);

    /**
     * Rows carried over from the V8 whitelist by V18. Shown in the UI so an
     * operator can replace a legacy coordinate with a purl-based exemption, and
     * used by the matcher to decide whether the legacy comparison is worth doing
     * at all.
     */
    List<FirewallExemptionEntity> findByKeyKind(FirewallComponentKeyKind keyKind);

    long countByKeyKind(FirewallComponentKeyKind keyKind);
}
