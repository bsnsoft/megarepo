package de.bsnsoft.megarepo.database.repository;

import de.bsnsoft.megarepo.database.entity.AuditLogEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface AuditLogJpaRepository extends JpaRepository<AuditLogEntity, Long> {

    Page<AuditLogEntity> findByRepositoryOrderByTimestampDesc(String repository, Pageable pageable);

    Page<AuditLogEntity> findByUserIdOrderByTimestampDesc(String userId, Pageable pageable);

    Page<AuditLogEntity> findByTimestampBetween(Instant from, Instant to, Pageable pageable);

    Page<AuditLogEntity> findByRepositoryAndTimestampBetween(
            String repository, Instant from, Instant to, Pageable pageable);

    Page<AuditLogEntity> findByAction(String action, Pageable pageable);

    Page<AuditLogEntity> findByRepositoryAndAction(String repository, String action, Pageable pageable);

    long countByActionAndTimestampAfter(String action, Instant after);

    @Query("SELECT COALESCE(SUM(a.size), 0) FROM AuditLogEntity a WHERE a.action = :action AND a.timestamp > :after")
    long sumSizeByActionAndTimestampAfter(@Param("action") String action, @Param("after") Instant after);

    List<AuditLogEntity> findTop50ByOrderByTimestampDesc();

    @Query("SELECT COUNT(DISTINCT a.userId) FROM AuditLogEntity a "
            + "WHERE a.timestamp > :since "
            + "AND a.userId IS NOT NULL AND a.userId <> 'anonymous'")
    int countDistinctActiveUsers(@Param("since") Instant since);
}
