package de.bsnsoft.megarepo.security.rbac;

import de.bsnsoft.megarepo.core.security.UserPrincipal;
import de.bsnsoft.megarepo.database.entity.PrivilegeEntity;
import de.bsnsoft.megarepo.database.entity.RoleEntity;
import de.bsnsoft.megarepo.database.repository.PrivilegeJpaRepository;
import de.bsnsoft.megarepo.database.repository.RoleJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PrivilegeEvaluatorImplTest {

    @Mock
    private RoleJpaRepository roleJpaRepository;

    @Mock
    private PrivilegeJpaRepository privilegeJpaRepository;

    private PrivilegeEvaluatorImpl privilegeEvaluator;

    @BeforeEach
    void setUp() {
        RoleResolver roleResolver = new RoleResolver(roleJpaRepository);
        privilegeEvaluator = new PrivilegeEvaluatorImpl(roleResolver, privilegeJpaRepository);
    }

    @Test
    void hasPermission_wildcardAllFields_grantsAccess() {
        RoleEntity role = createRole("nx-admin", Set.of("admin-all"), Set.of());
        PrivilegeEntity privilege = createPrivilege("admin-all", Map.of("actions", "*", "format", "*", "repository", "*"));

        when(roleJpaRepository.findById("nx-admin")).thenReturn(Optional.of(role));
        when(privilegeJpaRepository.findById("admin-all")).thenReturn(Optional.of(privilege));

        UserPrincipal user = new UserPrincipal("admin", Set.of("nx-admin"), "default");

        assertTrue(privilegeEvaluator.hasPermission(user, "read", "maven2", "releases"));
    }

    @Test
    void hasPermission_specificFormatAndRepo_grantsAccess() {
        RoleEntity role = createRole("maven-deployer", Set.of("maven-deploy"), Set.of());
        PrivilegeEntity privilege =
                createPrivilege("maven-deploy", Map.of("actions", "read,write", "format", "maven2", "repository", "releases"));

        when(roleJpaRepository.findById("maven-deployer")).thenReturn(Optional.of(role));
        when(privilegeJpaRepository.findById("maven-deploy")).thenReturn(Optional.of(privilege));

        UserPrincipal user = new UserPrincipal("deployer", Set.of("maven-deployer"), "default");

        assertTrue(privilegeEvaluator.hasPermission(user, "write", "maven2", "releases"));
    }

    @Test
    void hasPermission_wrongAction_deniesAccess() {
        RoleEntity role = createRole("reader", Set.of("read-only"), Set.of());
        PrivilegeEntity privilege =
                createPrivilege("read-only", Map.of("actions", "read", "format", "*", "repository", "*"));

        when(roleJpaRepository.findById("reader")).thenReturn(Optional.of(role));
        when(privilegeJpaRepository.findById("read-only")).thenReturn(Optional.of(privilege));

        UserPrincipal user = new UserPrincipal("viewer", Set.of("reader"), "default");

        assertFalse(privilegeEvaluator.hasPermission(user, "write", "maven2", "releases"));
    }

    @Test
    void hasPermission_wrongFormat_deniesAccess() {
        RoleEntity role = createRole("maven-reader", Set.of("maven-read"), Set.of());
        PrivilegeEntity privilege =
                createPrivilege("maven-read", Map.of("actions", "read", "format", "maven2", "repository", "*"));

        when(roleJpaRepository.findById("maven-reader")).thenReturn(Optional.of(role));
        when(privilegeJpaRepository.findById("maven-read")).thenReturn(Optional.of(privilege));

        UserPrincipal user = new UserPrincipal("viewer", Set.of("maven-reader"), "default");

        assertFalse(privilegeEvaluator.hasPermission(user, "read", "npm", "releases"));
    }

    @Test
    void hasPermission_nestedRoles_resolvesPrivileges() {
        RoleEntity parentRole = createRole("team-lead", Set.of(), Set.of("developer"));
        RoleEntity childRole = createRole("developer", Set.of("dev-priv"), Set.of());
        PrivilegeEntity privilege =
                createPrivilege("dev-priv", Map.of("actions", "read,write", "format", "*", "repository", "*"));

        when(roleJpaRepository.findById("team-lead")).thenReturn(Optional.of(parentRole));
        when(roleJpaRepository.findById("developer")).thenReturn(Optional.of(childRole));
        when(privilegeJpaRepository.findById("dev-priv")).thenReturn(Optional.of(privilege));

        UserPrincipal user = new UserPrincipal("lead", Set.of("team-lead"), "default");

        assertTrue(privilegeEvaluator.hasPermission(user, "write", "maven2", "snapshots"));
    }

    @Test
    void hasPermission_circularNestedRoles_handledGracefully() {
        RoleEntity roleA = createRole("role-a", Set.of("priv-a"), Set.of("role-b"));
        RoleEntity roleB = createRole("role-b", Set.of(), Set.of("role-a"));
        PrivilegeEntity privilege =
                createPrivilege("priv-a", Map.of("actions", "read", "format", "*", "repository", "*"));

        when(roleJpaRepository.findById("role-a")).thenReturn(Optional.of(roleA));
        when(roleJpaRepository.findById("role-b")).thenReturn(Optional.of(roleB));
        when(privilegeJpaRepository.findById("priv-a")).thenReturn(Optional.of(privilege));

        UserPrincipal user = new UserPrincipal("user1", Set.of("role-a"), "default");

        assertTrue(privilegeEvaluator.hasPermission(user, "read", "maven2", "releases"));
    }

    @Test
    void hasPermission_noRoles_deniesAccess() {
        UserPrincipal user = new UserPrincipal("nobody", Set.of(), "default");

        assertFalse(privilegeEvaluator.hasPermission(user, "read", "maven2", "releases"));
    }

    @Test
    void hasPermission_unknownRole_deniesAccess() {
        when(roleJpaRepository.findById("nonexistent")).thenReturn(Optional.empty());

        UserPrincipal user = new UserPrincipal("user1", Set.of("nonexistent"), "default");

        assertFalse(privilegeEvaluator.hasPermission(user, "read", "maven2", "releases"));
    }

    @Test
    void hasPermission_commaActions_matchesAnyAction() {
        RoleEntity role = createRole("deployer", Set.of("deploy-priv"), Set.of());
        PrivilegeEntity privilege =
                createPrivilege("deploy-priv", Map.of("actions", "read,write,delete", "format", "*", "repository", "*"));

        when(roleJpaRepository.findById("deployer")).thenReturn(Optional.of(role));
        when(privilegeJpaRepository.findById("deploy-priv")).thenReturn(Optional.of(privilege));

        UserPrincipal user = new UserPrincipal("user1", Set.of("deployer"), "default");

        assertTrue(privilegeEvaluator.hasPermission(user, "delete", "raw", "hosted-raw"));
    }

    private RoleEntity createRole(String id, Set<String> privileges, Set<String> nestedRoles) {
        RoleEntity role = new RoleEntity();
        role.setId(id);
        role.setName(id);
        role.setPrivileges(privileges);
        role.setNestedRoles(nestedRoles);
        role.setCreatedAt(Instant.now());
        role.setUpdatedAt(Instant.now());
        return role;
    }

    private PrivilegeEntity createPrivilege(String name, Map<String, Object> properties) {
        PrivilegeEntity privilege = new PrivilegeEntity();
        privilege.setName(name);
        privilege.setType("repository");
        privilege.setProperties(properties);
        privilege.setCreatedAt(Instant.now());
        return privilege;
    }
}
