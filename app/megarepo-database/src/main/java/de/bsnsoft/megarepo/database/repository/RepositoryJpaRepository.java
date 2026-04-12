package de.bsnsoft.megarepo.database.repository;

import de.bsnsoft.megarepo.database.entity.RepositoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RepositoryJpaRepository extends JpaRepository<RepositoryEntity, UUID> {

    Optional<RepositoryEntity> findByName(String name);

    List<RepositoryEntity> findByFormat(String format);

    List<RepositoryEntity> findByType(String type);
}
