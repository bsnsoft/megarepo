package de.bsnsoft.megarepo.database.repository;

import de.bsnsoft.megarepo.database.entity.NvdSyncStateEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface NvdSyncStateJpaRepository extends JpaRepository<NvdSyncStateEntity, Integer> {
}
