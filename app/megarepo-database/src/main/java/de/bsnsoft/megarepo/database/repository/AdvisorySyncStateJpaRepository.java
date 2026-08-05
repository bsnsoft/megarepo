package de.bsnsoft.megarepo.database.repository;

import de.bsnsoft.megarepo.database.entity.AdvisorySyncStateEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AdvisorySyncStateJpaRepository extends JpaRepository<AdvisorySyncStateEntity, String> {
}
