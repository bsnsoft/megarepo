package de.bsnsoft.megarepo.database.repository;

import de.bsnsoft.megarepo.database.entity.CveEntryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CveEntryJpaRepository extends JpaRepository<CveEntryEntity, String> {
}
