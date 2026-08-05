package de.bsnsoft.megarepo.database.repository;

import de.bsnsoft.megarepo.database.entity.CveEntryEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface CveEntryJpaRepository extends JpaRepository<CveEntryEntity, String> {

    /**
     * Keyset page over the local NVD mirror, used by {@code NvdAdvisorySource}
     * to normalise {@code cve_entries} into the advisory store without ever
     * touching the network.
     *
     * <p>Keyset rather than offset paging: the NVD sync writes into this table
     * concurrently, and an {@code OFFSET} page would silently skip or repeat
     * rows whenever it does. {@code last_modified} alone is not unique — an NVD
     * bulk update stamps thousands of CVEs with the same instant — so
     * {@code cve_id} is the tie-breaker that guarantees progress.
     *
     * @param lastModified the {@code last_modified} of the last row of the
     *     previous page, or {@link Instant#EPOCH} to start from the beginning
     * @param cveId the {@code cve_id} of that row, or {@code ""} to start from
     *     the beginning
     */
    @Query("""
            SELECT c FROM CveEntryEntity c
            WHERE c.lastModified > :lastModified
               OR (c.lastModified = :lastModified AND c.cveId > :cveId)
            ORDER BY c.lastModified ASC, c.cveId ASC
            """)
    List<CveEntryEntity> findModifiedAfter(
            @Param("lastModified") Instant lastModified,
            @Param("cveId") String cveId,
            Pageable pageable);
}
