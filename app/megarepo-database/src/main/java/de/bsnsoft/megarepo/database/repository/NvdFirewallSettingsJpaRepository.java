package de.bsnsoft.megarepo.database.repository;

import de.bsnsoft.megarepo.database.entity.NvdFirewallSettingsEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface NvdFirewallSettingsJpaRepository extends JpaRepository<NvdFirewallSettingsEntity, Integer> {
}
