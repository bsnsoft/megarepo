package de.bsnsoft.megarepo.database.repository;

import de.bsnsoft.megarepo.core.firewall.FirewallQuarantineState;
import de.bsnsoft.megarepo.database.entity.FirewallQuarantineEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FirewallQuarantineJpaRepository extends JpaRepository<FirewallQuarantineEntity, UUID> {

    /**
     * The request-path lookup. Served by the unique index behind
     * {@code firewall_quarantine_unique_component}, so it costs one index probe.
     */
    Optional<FirewallQuarantineEntity> findByRepositoryIdAndComponentKey(
            UUID repositoryId, String componentKey);

    Page<FirewallQuarantineEntity> findByStateOrderByFirstSeenDesc(
            FirewallQuarantineState state, Pageable pageable);

    Page<FirewallQuarantineEntity> findByRepositoryIdAndStateOrderByFirstSeenDesc(
            UUID repositoryId, FirewallQuarantineState state, Pageable pageable);

    Page<FirewallQuarantineEntity> findAllByOrderByFirstSeenDesc(Pageable pageable);

    long countByState(FirewallQuarantineState state);

    List<FirewallQuarantineEntity> findByComponentKeyAndState(
            String componentKey, FirewallQuarantineState state);

    /**
     * The re-evaluation sweep's work list: entries still held whose next
     * evaluation is due, or which have never been scheduled.
     *
     * <p>Null {@code nextEvaluationAt} sorts in as due on purpose — an entry the
     * sweep has never seen is the one most likely to be releasable, and an entry
     * whose schedule was lost to a crash must not sit in the queue forever.
     */
    @Query("""
            SELECT q FROM FirewallQuarantineEntity q
            WHERE q.state = de.bsnsoft.megarepo.core.firewall.FirewallQuarantineState.QUARANTINED
              AND (q.nextEvaluationAt IS NULL OR q.nextEvaluationAt <= :now)
            ORDER BY q.nextEvaluationAt ASC NULLS FIRST, q.firstSeen ASC
            """)
    List<FirewallQuarantineEntity> findDueForReevaluation(@Param("now") Instant now, Pageable pageable);

    /**
     * Records that a held component was asked for again, without loading the row.
     *
     * <p>A blocked download is retried by build tooling on every run, and reading
     * an entity, mutating it and flushing it for a counter would put an
     * unnecessary write conflict on the request path of exactly the components
     * that are being hammered.
     */
    @Modifying
    @Query("""
            UPDATE FirewallQuarantineEntity q
            SET q.hitCount = q.hitCount + 1, q.lastSeen = :seenAt
            WHERE q.id = :id
            """)
    int recordHit(@Param("id") UUID id, @Param("seenAt") Instant seenAt);

    /**
     * Entries in the given repositories whose decision came from a policy — used
     * when a policy changes, to schedule the affected entries for immediate
     * re-evaluation instead of waiting for the sweep.
     */
    List<FirewallQuarantineEntity> findByPolicyIdAndState(
            UUID policyId, FirewallQuarantineState state);
}
