package de.bsnsoft.megarepo.database.repository;

import de.bsnsoft.megarepo.database.entity.NvdFirewallWhitelistEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface NvdFirewallWhitelistJpaRepository extends JpaRepository<NvdFirewallWhitelistEntity, Long> {

    List<NvdFirewallWhitelistEntity> findByEntryType(String entryType);

    Optional<NvdFirewallWhitelistEntity> findByEntryTypeAndValue(String entryType, String value);
}
