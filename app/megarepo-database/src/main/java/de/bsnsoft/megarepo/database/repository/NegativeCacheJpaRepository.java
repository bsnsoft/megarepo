package de.bsnsoft.megarepo.database.repository;

import de.bsnsoft.megarepo.database.entity.NegativeCacheEntry;
import de.bsnsoft.megarepo.database.entity.NegativeCacheId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface NegativeCacheJpaRepository extends JpaRepository<NegativeCacheEntry, NegativeCacheId> {

    Optional<NegativeCacheEntry> findByRepositoryIdAndPath(UUID repositoryId, String path);

    void deleteByExpiresAtBefore(Instant instant);

    long countByRepositoryId(UUID repositoryId);

    void deleteByRepositoryId(UUID repositoryId);
}
