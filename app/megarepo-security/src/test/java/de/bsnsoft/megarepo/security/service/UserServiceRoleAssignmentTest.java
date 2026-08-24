package de.bsnsoft.megarepo.security.service;

import de.bsnsoft.megarepo.database.entity.UserEntity;
import de.bsnsoft.megarepo.database.repository.UserJpaRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The second line of defense: only an administrator may hand out roles.
 *
 * <p>{@code SecurityConfig}'s filter chain already keeps non-admins off the user
 * API, and {@code SecurityAdminAuthorizationTest} in {@code megarepo-rest-api}
 * proves it does. This covers the same escalation one layer down, at the point
 * where roles are actually written, so that it stays blocked if a request ever
 * reaches the service by a route the chain does not name — a new controller, a
 * renamed prefix, an endpoint mapped outside {@code /api/v1}. That is not
 * hypothetical: it is exactly how the user API came to be reachable by every
 * logged-in account in the first place, and a path-pattern list gives no compile
 * error when something falls out of it.
 *
 * <p>Scoped to role changes rather than to user administration as a whole. The
 * chain owns "who may call this endpoint"; this owns "who may grant privilege",
 * which is the step that actually turns a reader into an administrator.
 */
@ExtendWith(MockitoExtension.class)
class UserServiceRoleAssignmentTest {

    @Mock private UserJpaRepository userJpaRepository;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(userJpaRepository, new BCryptPasswordEncoder(4));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("a non-admin cannot create an account that holds a role")
    void nonAdminCannotCreateUserWithRoles() {
        authenticateAs("reader", "ROLE_nx-viewer");

        assertThatThrownBy(() -> userService.createUser(
                        "backdoor", "Back", "Door", "b@d.example", "hunter2hunter2", Set.of("nx-admin")))
                .isInstanceOf(AccessDeniedException.class);

        verify(userJpaRepository, never()).save(any());
    }

    @Test
    @DisplayName("a non-admin cannot promote an existing account")
    void nonAdminCannotPromoteExistingUser() {
        authenticateAs("reader", "ROLE_nx-viewer");
        when(userJpaRepository.findById("reader")).thenReturn(Optional.of(existing("reader", "nx-viewer")));

        assertThatThrownBy(() -> userService.updateUser(
                        "reader", "R", "Eader", "r@d.example", "ACTIVE", Set.of("nx-viewer", "nx-admin")))
                .isInstanceOf(AccessDeniedException.class);

        verify(userJpaRepository, never()).save(any());
    }

    /**
     * Stripping a role is a privilege change too — locking the only
     * administrator out is its own kind of damage — so the guard compares sets
     * rather than looking only for additions.
     */
    @Test
    @DisplayName("a non-admin cannot strip a role off an administrator")
    void nonAdminCannotDemoteAdministrator() {
        authenticateAs("reader", "ROLE_nx-viewer");
        when(userJpaRepository.findById("admin")).thenReturn(Optional.of(existing("admin", "nx-admin")));

        assertThatThrownBy(
                        () -> userService.updateUser("admin", "A", "Dmin", "a@d.example", "ACTIVE", Set.of()))
                .isInstanceOf(AccessDeniedException.class);

        verify(userJpaRepository, never()).save(any());
    }

    /**
     * Fails closed with no caller at all, so that a future internal or bootstrap
     * caller cannot become a quiet way around this. Nothing calls the service
     * that way today: the first administrator is seeded in SQL, and LDAP logins
     * write roles onto the entity directly.
     */
    @Test
    @DisplayName("an unauthenticated caller cannot grant a role either")
    void unauthenticatedCallerCannotGrantRoles() {
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> userService.createUser(
                        "backdoor", "Back", "Door", "b@d.example", "hunter2hunter2", Set.of("nx-admin")))
                .isInstanceOf(AccessDeniedException.class);

        verify(userJpaRepository, never()).save(any());
    }

    @Test
    @DisplayName("an administrator may grant roles")
    void adminMayGrantRoles() {
        authenticateAs("admin", "ROLE_nx-admin");
        when(userJpaRepository.existsById("newuser")).thenReturn(false);
        when(userJpaRepository.save(any(UserEntity.class))).thenAnswer(i -> i.getArgument(0));

        UserEntity created = userService.createUser(
                "newuser", "New", "User", "n@d.example", "hunter2hunter2", Set.of("nx-admin"));

        assertThat(created.getRoles()).containsExactly("nx-admin");
    }

    /**
     * The guard is about privilege, not about the endpoint. A profile edit that
     * leaves the role set alone grants nothing, so it is the chain's call
     * whether it is allowed — this layer stays out of it rather than drifting
     * into a second, divergent copy of the endpoint rule.
     */
    @Test
    @DisplayName("an update that leaves the roles alone is not blocked here")
    void unchangedRolesAreNotGuarded() {
        authenticateAs("reader", "ROLE_nx-viewer");
        when(userJpaRepository.findById("reader")).thenReturn(Optional.of(existing("reader", "nx-viewer")));
        when(userJpaRepository.save(any(UserEntity.class))).thenAnswer(i -> i.getArgument(0));

        UserEntity updated = userService.updateUser(
                "reader", "Renamed", "Eader", "r@d.example", "ACTIVE", Set.of("nx-viewer"));

        assertThat(updated.getFirstName()).isEqualTo("Renamed");
        assertThat(updated.getRoles()).containsExactly("nx-viewer");
    }

    private static UserEntity existing(String userId, String... roles) {
        var entity = new UserEntity();
        entity.setUserId(userId);
        entity.setRoles(new HashSet<>(List.of(roles)));
        return entity;
    }

    private static void authenticateAs(String userId, String authority) {
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(
                        userId, null, List.of(new SimpleGrantedAuthority(authority))));
    }
}
