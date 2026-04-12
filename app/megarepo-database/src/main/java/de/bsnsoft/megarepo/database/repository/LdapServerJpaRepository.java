package de.bsnsoft.megarepo.database.repository;

import de.bsnsoft.megarepo.database.entity.LdapServerEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LdapServerJpaRepository extends JpaRepository<LdapServerEntity, UUID> {

    List<LdapServerEntity> findAllByOrderBySortOrder();

    Optional<LdapServerEntity> findByName(String name);

    List<LdapServerEntity> findAllByEnabledTrueOrderBySortOrder();
}
