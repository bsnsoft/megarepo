package de.bsnsoft.megarepo.database.repository;

import de.bsnsoft.megarepo.database.entity.AssetEntity;
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
public interface AssetJpaRepository extends JpaRepository<AssetEntity, UUID> {

    Optional<AssetEntity> findByRepositoryIdAndPath(UUID repositoryId, String path);

    Page<AssetEntity> findByRepositoryId(UUID repositoryId, Pageable pageable);

    List<AssetEntity> findAllByRepositoryId(UUID repositoryId);

    Page<AssetEntity> findByComponentId(UUID componentId, Pageable pageable);

    long countByRepositoryId(UUID repositoryId);

    @Query("SELECT SUM(a.size) FROM AssetEntity a WHERE a.repositoryId = :repoId")
    Long sumSizeByRepositoryId(@Param("repoId") UUID repositoryId);

    @Query("SELECT MIN(a.createdAt) FROM AssetEntity a WHERE a.repositoryId = :repoId")
    Instant findOldestCreatedAtByRepositoryId(@Param("repoId") UUID repositoryId);

    @Query("SELECT MAX(a.createdAt) FROM AssetEntity a WHERE a.repositoryId = :repoId")
    Instant findNewestCreatedAtByRepositoryId(@Param("repoId") UUID repositoryId);

    List<AssetEntity> findByRepositoryIdAndPathStartingWith(UUID repositoryId, String pathPrefix);

    List<AssetEntity> findByRepositoryIdAndChecksumSha256(UUID repositoryId, String checksumSha256);

    @Query("SELECT COALESCE(SUM(a.size), 0) FROM AssetEntity a")
    long sumTotalSize();

    @Query("SELECT COUNT(a) FROM AssetEntity a WHERE a.repositoryId IN "
            + "(SELECT r.id FROM RepositoryEntity r WHERE r.blobStoreName = :blobStoreName)")
    long countByBlobStoreName(@Param("blobStoreName") String blobStoreName);

    @Query("SELECT COALESCE(SUM(a.size), 0) FROM AssetEntity a WHERE a.repositoryId IN "
            + "(SELECT r.id FROM RepositoryEntity r WHERE r.blobStoreName = :blobStoreName)")
    long sumSizeByBlobStoreName(@Param("blobStoreName") String blobStoreName);
}
