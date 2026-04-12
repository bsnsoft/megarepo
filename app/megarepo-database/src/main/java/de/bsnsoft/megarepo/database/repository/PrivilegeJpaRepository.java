package de.bsnsoft.megarepo.database.repository;

import de.bsnsoft.megarepo.database.entity.PrivilegeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PrivilegeJpaRepository extends JpaRepository<PrivilegeEntity, String> {

    List<PrivilegeEntity> findByType(String type);
}
