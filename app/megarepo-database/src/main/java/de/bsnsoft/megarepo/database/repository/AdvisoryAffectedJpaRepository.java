package de.bsnsoft.megarepo.database.repository;

import de.bsnsoft.megarepo.database.entity.AdvisoryAffectedEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

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

    List<AdvisoryAffectedEntity> findByAdvisoryId(String advisoryId);

    @Modifying
    @Query("DELETE FROM AdvisoryAffectedEntity a WHERE a.advisoryId = :advisoryId")
    int deleteByAdvisoryId(@Param("advisoryId") String advisoryId);
}
