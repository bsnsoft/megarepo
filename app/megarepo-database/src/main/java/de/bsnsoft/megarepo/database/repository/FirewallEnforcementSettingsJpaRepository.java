package de.bsnsoft.megarepo.database.repository;

import de.bsnsoft.megarepo.database.entity.FirewallEnforcementSettingsEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface FirewallEnforcementSettingsJpaRepository
        extends JpaRepository<FirewallEnforcementSettingsEntity, Integer> {
}
