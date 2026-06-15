package de.bsnsoft.megarepo.database.repository;

import de.bsnsoft.megarepo.database.entity.OutboundProxySettingsEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface OutboundProxySettingsJpaRepository
        extends JpaRepository<OutboundProxySettingsEntity, Integer> {
}
