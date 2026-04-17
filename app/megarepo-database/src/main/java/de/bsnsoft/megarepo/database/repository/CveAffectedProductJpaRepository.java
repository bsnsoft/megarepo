package de.bsnsoft.megarepo.database.repository;

import de.bsnsoft.megarepo.database.entity.CveAffectedProductEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface CveAffectedProductJpaRepository extends JpaRepository<CveAffectedProductEntity, Long> {

    List<CveAffectedProductEntity> findByProductIn(Collection<String> products);

    @Modifying
    @Query("DELETE FROM CveAffectedProductEntity p WHERE p.cveId = :cveId")
    int deleteByCveId(@Param("cveId") String cveId);

    @Modifying
    @Query("DELETE FROM CveAffectedProductEntity p WHERE p.cveId IN :cveIds")
    int deleteByCveIdIn(@Param("cveIds") Collection<String> cveIds);
}
