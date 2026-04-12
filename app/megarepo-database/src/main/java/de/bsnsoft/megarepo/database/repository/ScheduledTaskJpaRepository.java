package de.bsnsoft.megarepo.database.repository;

import de.bsnsoft.megarepo.database.entity.ScheduledTaskEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ScheduledTaskJpaRepository extends JpaRepository<ScheduledTaskEntity, UUID> {

    List<ScheduledTaskEntity> findByType(String type);
}
