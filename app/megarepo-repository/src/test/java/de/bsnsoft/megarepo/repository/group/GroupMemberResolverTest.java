package de.bsnsoft.megarepo.repository.group;

import de.bsnsoft.megarepo.core.repository.RepositoryConfig;
import de.bsnsoft.megarepo.core.repository.RepositoryConfigService;
import de.bsnsoft.megarepo.core.repository.RepositoryType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GroupMemberResolverTest {

    @Mock
    private RepositoryConfigService repositoryConfigService;

    private GroupMemberResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new GroupMemberResolver(repositoryConfigService);
    }

    @Test
    void resolveMembers_returnsOrderedMembers() {
        var groupId = UUID.randomUUID();
        var groupRepo = groupRepo(groupId, Map.of());
        var member1 = hostedRepo("member-1", true);
        var member2 = hostedRepo("member-2", true);
        var member3 = proxyRepo("member-3", true);

        when(repositoryConfigService.getGroupMembers(groupId))
                .thenReturn(List.of(member1, member2, member3));

        List<RepositoryConfig> result = resolver.resolveMembers(groupRepo);

        assertEquals(3, result.size());
        assertEquals("member-1", result.get(0).name());
        assertEquals("member-2", result.get(1).name());
        assertEquals("member-3", result.get(2).name());
    }

    @Test
    void resolveMembers_skipsOfflineMembers() {
        var groupId = UUID.randomUUID();
        var groupRepo = groupRepo(groupId, Map.of());
        var member1 = hostedRepo("member-1", true);
        var offlineMember = hostedRepo("member-offline", false);
        var member3 = hostedRepo("member-3", true);

        when(repositoryConfigService.getGroupMembers(groupId))
                .thenReturn(List.of(member1, offlineMember, member3));

        List<RepositoryConfig> result = resolver.resolveMembers(groupRepo);

        assertEquals(2, result.size());
        assertEquals("member-1", result.get(0).name());
        assertEquals("member-3", result.get(1).name());
    }

    @Test
    void resolveMembers_emptyGroup_returnsEmptyList() {
        var groupId = UUID.randomUUID();
        var groupRepo = groupRepo(groupId, Map.of());

        when(repositoryConfigService.getGroupMembers(groupId))
                .thenReturn(List.of());

        List<RepositoryConfig> result = resolver.resolveMembers(groupRepo);

        assertTrue(result.isEmpty());
    }

    @Test
    void getWritableMember_returnsConfiguredMember() {
        var groupId = UUID.randomUUID();
        var attrs = Map.<String, Object>of("group", Map.of("writableMember", "hosted-releases"));
        var groupRepo = groupRepo(groupId, attrs);
        var writableMember = hostedRepo("hosted-releases", true);

        when(repositoryConfigService.getRepository("hosted-releases"))
                .thenReturn(Optional.of(writableMember));

        Optional<RepositoryConfig> result = resolver.getWritableMember(groupRepo);

        assertTrue(result.isPresent());
        assertEquals("hosted-releases", result.get().name());
    }

    @Test
    void getWritableMember_noGroupAttribute_returnsEmpty() {
        var groupId = UUID.randomUUID();
        var groupRepo = groupRepo(groupId, Map.of());

        Optional<RepositoryConfig> result = resolver.getWritableMember(groupRepo);

        assertTrue(result.isEmpty());
    }

    @Test
    void getWritableMember_blankMemberName_returnsEmpty() {
        var groupId = UUID.randomUUID();
        var attrs = Map.<String, Object>of("group", Map.of("writableMember", "  "));
        var groupRepo = groupRepo(groupId, attrs);

        Optional<RepositoryConfig> result = resolver.getWritableMember(groupRepo);

        assertTrue(result.isEmpty());
    }

    private RepositoryConfig groupRepo(UUID id, Map<String, Object> attributes) {
        return new RepositoryConfig(id, "my-group", "maven2", RepositoryType.GROUP, true, "default", attributes);
    }

    private RepositoryConfig hostedRepo(String name, boolean online) {
        return new RepositoryConfig(UUID.randomUUID(), name, "maven2", RepositoryType.HOSTED, online, "default", Map.of());
    }

    private RepositoryConfig proxyRepo(String name, boolean online) {
        return new RepositoryConfig(UUID.randomUUID(), name, "maven2", RepositoryType.PROXY, online, "default", Map.of());
    }
}
