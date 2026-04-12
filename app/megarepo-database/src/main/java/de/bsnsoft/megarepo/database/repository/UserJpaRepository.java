package de.bsnsoft.megarepo.database.repository;

import de.bsnsoft.megarepo.database.entity.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserJpaRepository extends JpaRepository<UserEntity, String> {

    long countByStatus(String status);
}
