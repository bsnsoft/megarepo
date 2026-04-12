package de.bsnsoft.megarepo.core.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RepositoryConfigService {

    Optional<RepositoryConfig> getRepository(String name);

    List<RepositoryConfig> getAllRepositories();

    List<RepositoryConfig> getGroupMembers(UUID groupRepoId);
}
