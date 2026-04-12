package de.bsnsoft.megarepo.database.repository;

import de.bsnsoft.megarepo.database.entity.AnonymousAccessSettingsEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AnonymousAccessJpaRepository extends JpaRepository<AnonymousAccessSettingsEntity, Integer> {
}
