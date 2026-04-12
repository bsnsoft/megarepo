package de.bsnsoft.megarepo.database.repository;

import de.bsnsoft.megarepo.database.entity.RoleEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RoleJpaRepository extends JpaRepository<RoleEntity, String> {
}
