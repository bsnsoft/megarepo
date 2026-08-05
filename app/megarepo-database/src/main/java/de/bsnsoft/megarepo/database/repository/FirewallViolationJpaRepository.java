package de.bsnsoft.megarepo.database.repository;

import de.bsnsoft.megarepo.core.firewall.FirewallRuleType;
import de.bsnsoft.megarepo.database.entity.FirewallViolationEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
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
}
