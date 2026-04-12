package de.bsnsoft.megarepo.repository;

import de.bsnsoft.megarepo.core.repository.RepositoryConfig;
import de.bsnsoft.megarepo.core.repository.RepositoryType;
import de.bsnsoft.megarepo.database.entity.GroupMemberEntity;
import de.bsnsoft.megarepo.database.entity.RepositoryEntity;
import de.bsnsoft.megarepo.database.repository.GroupMemberJpaRepository;
import de.bsnsoft.megarepo.database.repository.RepositoryJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RepositoryConfigServiceImplTest {

    @Mock
    private RepositoryJpaRepository repositoryJpaRepository;

    @Mock
    private GroupMemberJpaRepository groupMemberJpaRepository;

    private RepositoryConfigServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new RepositoryConfigServiceImpl(repositoryJpaRepository, groupMemberJpaRepository);
    }

    @Test
    void getRepository_found() {
        var entity = createRepoEntity("my-repo", "maven2", "HOSTED");
        when(repositoryJpaRepository.findByName("my-repo")).thenReturn(Optional.of(entity));

        Optional<RepositoryConfig> result = service.getRepository("my-repo");

        assertTrue(result.isPresent());
        RepositoryConfig config = result.get();
        assertEquals("my-repo", config.name());
        assertEquals("maven2", config.format());
        assertEquals(RepositoryType.HOSTED, config.type());
        assertTrue(config.online());
    }

    @Test
    void getRepository_notFound() {
        when(repositoryJpaRepository.findByName("missing")).thenReturn(Optional.empty());

        Optional<RepositoryConfig> result = service.getRepository("missing");

        assertFalse(result.isPresent());
    }

    @Test
    void getAllRepositories() {
        var entity1 = createRepoEntity("repo-1", "maven2", "HOSTED");
        var entity2 = createRepoEntity("repo-2", "npm", "PROXY");
        when(repositoryJpaRepository.findAll()).thenReturn(List.of(entity1, entity2));

        List<RepositoryConfig> result = service.getAllRepositories();

        assertEquals(2, result.size());
        assertEquals("repo-1", result.get(0).name());
        assertEquals("repo-2", result.get(1).name());
    }

    @Test
    void getGroupMembers_returnsOrderedMembers() {
        UUID groupId = UUID.randomUUID();
        UUID member1Id = UUID.randomUUID();
        UUID member2Id = UUID.randomUUID();

        var gm1 = new GroupMemberEntity();
        gm1.setGroupRepoId(groupId);
        gm1.setMemberRepoId(member1Id);
        gm1.setSortOrder(0);
        var gm2 = new GroupMemberEntity();
        gm2.setGroupRepoId(groupId);
        gm2.setMemberRepoId(member2Id);
        gm2.setSortOrder(1);

        var memberEntity1 = createRepoEntity("member-1", "maven2", "HOSTED");
        memberEntity1.setId(member1Id);
        var memberEntity2 = createRepoEntity("member-2", "maven2", "PROXY");
        memberEntity2.setId(member2Id);

        when(groupMemberJpaRepository.findByGroupRepoIdOrderBySortOrder(groupId))
                .thenReturn(List.of(gm1, gm2));
        when(repositoryJpaRepository.findById(member1Id)).thenReturn(Optional.of(memberEntity1));
        when(repositoryJpaRepository.findById(member2Id)).thenReturn(Optional.of(memberEntity2));

        List<RepositoryConfig> result = service.getGroupMembers(groupId);

        assertEquals(2, result.size());
        assertEquals("member-1", result.get(0).name());
        assertEquals("member-2", result.get(1).name());
    }

    @Test
    void getGroupMembers_skipsMissingMembers() {
        UUID groupId = UUID.randomUUID();
        UUID member1Id = UUID.randomUUID();
        UUID missingId = UUID.randomUUID();

        var gm1 = new GroupMemberEntity();
        gm1.setGroupRepoId(groupId);
        gm1.setMemberRepoId(member1Id);
        gm1.setSortOrder(0);
        var gm2 = new GroupMemberEntity();
        gm2.setGroupRepoId(groupId);
        gm2.setMemberRepoId(missingId);
        gm2.setSortOrder(1);

        var memberEntity1 = createRepoEntity("member-1", "maven2", "HOSTED");
        memberEntity1.setId(member1Id);

        when(groupMemberJpaRepository.findByGroupRepoIdOrderBySortOrder(groupId))
                .thenReturn(List.of(gm1, gm2));
        when(repositoryJpaRepository.findById(member1Id)).thenReturn(Optional.of(memberEntity1));
        when(repositoryJpaRepository.findById(missingId)).thenReturn(Optional.empty());

        List<RepositoryConfig> result = service.getGroupMembers(groupId);

        assertEquals(1, result.size());
        assertEquals("member-1", result.get(0).name());
    }

    private RepositoryEntity createRepoEntity(String name, String format, String type) {
        var entity = new RepositoryEntity();
        entity.setId(UUID.randomUUID());
        entity.setName(name);
        entity.setFormat(format);
        entity.setType(type);
        entity.setOnline(true);
        entity.setBlobStoreName("default");
        entity.setAttributes(Map.of());
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        return entity;
    }
}
