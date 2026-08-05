package de.bsnsoft.megarepo.database.repository;

import de.bsnsoft.megarepo.core.firewall.FirewallRuleType;
import de.bsnsoft.megarepo.database.entity.FirewallViolationEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FirewallViolationJpaRepository extends JpaRepository<FirewallViolationEntity, Long> {

    /**
     * The newest violation recorded for one component in one repository under
     * one rule type — the row the AUDIT recorder compares against before writing
     * another one.
     *
     * <p>Backed by {@code idx_firewall_violation_repository} and
     * {@code idx_firewall_violation_purl} (V13). {@code repositoryId} must not be
     * null: the generated predicate binds it as {@code = ?}, which never matches
     * the rows whose repository has since been deleted, so a null argument would
     * silently disable de-duplication instead of widening it.
     */
    Optional<FirewallViolationEntity> findFirstByRepositoryIdAndPurlAndRuleTypeOrderByOccurredAtDesc(
            UUID repositoryId, String purl, FirewallRuleType ruleType);

    Page<FirewallViolationEntity> findAllByOrderByOccurredAtDesc(Pageable pageable);

    Page<FirewallViolationEntity> findByRepositoryIdOrderByOccurredAtDesc(UUID repositoryId, Pageable pageable);

    Page<FirewallViolationEntity> findByPurlOrderByOccurredAtDesc(String purl, Pageable pageable);

    long countByOccurredAtAfter(Instant since);

    /**
     * How many violations each repository has accumulated since {@code since},
     * in one grouped query.
     *
     * <p>The administration overview lists every repository with its count. Doing
     * that with a count per row turns a page render into one query per
     * repository; this is the same answer in one round trip.
     *
     * <p>Rows whose repository has been deleted ({@code repository_id IS NULL},
     * see V13) are excluded — they belong to no row in the overview. They are
     * still returned by the violation list itself, which keeps
     * {@code repository_name}.
     */
    @Query(
            """
            SELECT v.repositoryId AS repositoryId, COUNT(v) AS violations
            FROM FirewallViolationEntity v
            WHERE v.repositoryId IS NOT NULL AND v.occurredAt >= :since
            GROUP BY v.repositoryId
            """)
    List<RepositoryViolationCount> countByRepositorySince(@Param("since") Instant since);

    /** Projection for {@link #countByRepositorySince(Instant)}. */
    interface RepositoryViolationCount {
        UUID getRepositoryId();

        long getViolations();
    }
}
