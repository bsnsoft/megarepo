package de.bsnsoft.megarepo.database.repository;

import de.bsnsoft.megarepo.database.entity.ComponentEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ComponentJpaRepository extends JpaRepository<ComponentEntity, UUID> {

    Optional<ComponentEntity> findByRepositoryIdAndNamespaceAndNameAndVersion(
            UUID repositoryId, String namespace, String name, String version);

    Page<ComponentEntity> findByRepositoryId(UUID repositoryId, Pageable pageable);

    @org.springframework.data.jpa.repository.Query(
            "SELECT c FROM ComponentEntity c WHERE c.repositoryId = :repositoryId "
                    + "AND (LOWER(c.name) LIKE LOWER(CONCAT('%', :filter, '%')) "
                    + "OR LOWER(c.namespace) LIKE LOWER(CONCAT('%', :filter, '%')) "
                    + "OR LOWER(c.version) LIKE LOWER(CONCAT('%', :filter, '%')))")
    Page<ComponentEntity> findByRepositoryIdAndFilter(
            @org.springframework.data.repository.query.Param("repositoryId") UUID repositoryId,
            @org.springframework.data.repository.query.Param("filter") String filter,
            Pageable pageable);

    Page<ComponentEntity> findByRepositoryIdIn(List<UUID> repositoryIds, Pageable pageable);

    @org.springframework.data.jpa.repository.Query(
            "SELECT c FROM ComponentEntity c WHERE c.repositoryId IN :repositoryIds "
                    + "AND (LOWER(c.name) LIKE LOWER(CONCAT('%', :filter, '%')) "
                    + "OR LOWER(c.namespace) LIKE LOWER(CONCAT('%', :filter, '%')) "
                    + "OR LOWER(c.version) LIKE LOWER(CONCAT('%', :filter, '%')))")
    Page<ComponentEntity> findByRepositoryIdInAndFilter(
            @org.springframework.data.repository.query.Param("repositoryIds") List<UUID> repositoryIds,
            @org.springframework.data.repository.query.Param("filter") String filter,
            Pageable pageable);

    List<ComponentEntity> findByRepositoryIdAndNamespaceAndName(UUID repositoryId, String namespace, String name);

    long countByRepositoryId(UUID repositoryId);

    /**
     * Paged scan for the CPE/purl comparison report.
     *
     * <p>Returns a {@link List} rather than a {@link Page} on purpose: Spring
     * Data issues a {@code COUNT(*)} alongside every full {@code Page}, and the
     * report walks the whole table. On a large instance that would be one
     * sequential count per batch for a total this caller does not need — it
     * stops when a batch comes back short. The {@code IdNotNull} predicate is
     * always true and exists only because a derived query needs one.
     */
    List<ComponentEntity> findAllByIdNotNull(Pageable pageable);

    /** The same scan restricted to a set of repositories. */
    List<ComponentEntity> findAllByRepositoryIdIn(List<UUID> repositoryIds, Pageable pageable);
}
