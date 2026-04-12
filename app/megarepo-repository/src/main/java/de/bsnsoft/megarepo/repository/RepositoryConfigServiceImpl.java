package de.bsnsoft.megarepo.repository;

import de.bsnsoft.megarepo.core.repository.RepositoryConfig;
import de.bsnsoft.megarepo.core.repository.RepositoryConfigService;
import de.bsnsoft.megarepo.core.repository.RepositoryType;
import de.bsnsoft.megarepo.database.entity.GroupMemberEntity;
import de.bsnsoft.megarepo.database.entity.RepositoryEntity;
import de.bsnsoft.megarepo.database.repository.GroupMemberJpaRepository;
import de.bsnsoft.megarepo.database.repository.RepositoryJpaRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class RepositoryConfigServiceImpl implements RepositoryConfigService {

    private final RepositoryJpaRepository repositoryJpaRepository;
    private final GroupMemberJpaRepository groupMemberJpaRepository;

    public RepositoryConfigServiceImpl(
            RepositoryJpaRepository repositoryJpaRepository,
            GroupMemberJpaRepository groupMemberJpaRepository) {
        this.repositoryJpaRepository = repositoryJpaRepository;
        this.groupMemberJpaRepository = groupMemberJpaRepository;
    }

    @Override
    public Optional<RepositoryConfig> getRepository(String name) {
        return repositoryJpaRepository.findByName(name).map(this::toConfig);
    }

    @Override
    public List<RepositoryConfig> getAllRepositories() {
        return repositoryJpaRepository.findAll().stream()
                .map(this::toConfig)
                .toList();
    }

    @Override
    public List<RepositoryConfig> getGroupMembers(UUID groupRepoId) {
        List<GroupMemberEntity> members =
                groupMemberJpaRepository.findByGroupRepoIdOrderBySortOrder(groupRepoId);
        return members.stream()
                .map(member -> repositoryJpaRepository.findById(member.getMemberRepoId()))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .map(this::toConfig)
                .toList();
    }

    private RepositoryConfig toConfig(RepositoryEntity entity) {
        return new RepositoryConfig(
                entity.getId(),
                entity.getName(),
                entity.getFormat(),
                RepositoryType.valueOf(entity.getType()),
                entity.isOnline(),
                entity.getBlobStoreName(),
                entity.getAttributes());
    }
}
