package de.bsnsoft.megarepo.database.repository;

import de.bsnsoft.megarepo.database.entity.FirewallViolationEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.UUID;

@Repository
public interface FirewallViolationJpaRepository extends JpaRepository<FirewallViolationEntity, Long> {

    Page<FirewallViolationEntity> findAllByOrderByOccurredAtDesc(Pageable pageable);

    Page<FirewallViolationEntity> findByRepositoryIdOrderByOccurredAtDesc(UUID repositoryId, Pageable pageable);

    Page<FirewallViolationEntity> findByPurlOrderByOccurredAtDesc(String purl, Pageable pageable);

    long countByOccurredAtAfter(Instant since);
}
