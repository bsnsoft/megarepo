package de.bsnsoft.megarepo.database.repository;

import de.bsnsoft.megarepo.database.entity.AdvisoryAffectedEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface AdvisoryAffectedJpaRepository extends JpaRepository<AdvisoryAffectedEntity, UUID> {

    List<AdvisoryAffectedEntity> findByPurlTypeAndPurlNamespaceAndPurlName(
            String purlType, String purlNamespace, String purlName);

    List<AdvisoryAffectedEntity> findByPurlTypeAndPurlNamespaceIsNullAndPurlName(
            String purlType, String purlName);

    /**
     * Request-path lookup, backed by {@code idx_advisory_affected_purl}. Formats
     * without a namespace (unscoped npm, PyPI) store NULL, and {@code = NULL}
     * never matches in SQL, so the two cases need different predicates.
     *
     * <p>Split into two derived queries on purpose. Expressing it as a single
     * JPQL query with {@code (:ns IS NULL AND a.purlNamespace IS NULL OR
     * a.purlNamespace = :ns)} compiles but fails at runtime on PostgreSQL with
     * "could not determine data type of parameter" — the driver has no type
     * information for a bare parameter used in an IS NULL test. Dispatching in
     * Java avoids casting gymnastics and keeps both branches index-friendly.
     */
    default List<AdvisoryAffectedEntity> findByPurlCoordinates(
            String purlType, String purlNamespace, String purlName) {
        return purlNamespace == null
                ? findByPurlTypeAndPurlNamespaceIsNullAndPurlName(purlType, purlName)
                : findByPurlTypeAndPurlNamespaceAndPurlName(purlType, purlNamespace, purlName);
    }

    /**
     * Request-path lookup for CPE-derived rows, backed by
     * {@code idx_advisory_affected_purl_name} (V14).
     *
     * <p>Advisories that come from NVD are stored with the reserved purl type
     * {@code cpe} because a CPE names no ecosystem and its vendor is an
     * organisation rather than a purl namespace. They can therefore only be
     * matched on the product name, which is why this query skips
     * {@code purl_namespace} — and why it needs an index of its own rather than
     * a prefix of {@code idx_advisory_affected_purl}.
     */
    List<AdvisoryAffectedEntity> findByPurlTypeAndPurlNameIn(
            String purlType, Collection<String> purlNames);

    List<AdvisoryAffectedEntity> findByAdvisoryId(String advisoryId);

    @Modifying
    @Query("DELETE FROM AdvisoryAffectedEntity a WHERE a.advisoryId = :advisoryId")
    int deleteByAdvisoryId(@Param("advisoryId") String advisoryId);

    /**
     * Bulk variant used by the advisory ingest to make a re-sync idempotent:
     * {@code advisory_affected} has a surrogate key and no natural unique
     * constraint, so ranges are replaced per advisory rather than upserted.
     * Mirrors {@code CveAffectedProductJpaRepository#deleteByCveIdIn}.
     */
    @Modifying
    @Query("DELETE FROM AdvisoryAffectedEntity a WHERE a.advisoryId IN :advisoryIds")
    int deleteByAdvisoryIdIn(@Param("advisoryIds") Collection<String> advisoryIds);
}
