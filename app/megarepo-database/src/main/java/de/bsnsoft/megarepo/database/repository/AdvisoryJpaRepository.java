package de.bsnsoft.megarepo.database.repository;

import de.bsnsoft.megarepo.database.entity.AdvisoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface AdvisoryJpaRepository extends JpaRepository<AdvisoryEntity, String> {

    List<AdvisoryEntity> findBySource(String source);

    List<AdvisoryEntity> findByIdIn(Collection<String> ids);

    /** Withdrawn advisories must not produce findings. */
    List<AdvisoryEntity> findByIdInAndWithdrawnAtIsNull(Collection<String> ids);
}
