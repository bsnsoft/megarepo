package de.bsnsoft.megarepo.security.service;

import de.bsnsoft.megarepo.core.security.UserStatus;
import de.bsnsoft.megarepo.database.entity.UserEntity;
import de.bsnsoft.megarepo.database.repository.UserJpaRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class UserService {

    /**
     * The authority a caller must hold to hand out roles, in the
     * {@code ROLE_}-prefixed form that reaches the security context. Kept as a
     * literal rather than referencing {@code SecurityConfig} so that this check
     * does not depend on the class whose omissions it exists to survive.
     */
    private static final String ADMIN_AUTHORITY = "ROLE_nx-admin";

    private final UserJpaRepository userJpaRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserJpaRepository userJpaRepository, PasswordEncoder passwordEncoder) {
        this.userJpaRepository = userJpaRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Second line of defense against privilege escalation: only an administrator
     * may grant or revoke roles.
     *
     * <p>The filter chain in {@code SecurityConfig} already keeps non-admins off
     * the user-administration endpoints, and that is where authorization in this
     * project belongs. This check exists because that arrangement is one
     * oversight away from failing: authorization lives in a path-pattern list,
     * so a controller mapped under a prefix nobody thought to name inherits
     * {@code /api/v1/** -> authenticated()} and is wide open, silently and with
     * no compile error. That is precisely how the user API came to be reachable
     * by every logged-in account. Role assignment is the escalation primitive —
     * whoever can set {@code roles} can become an administrator and then do
     * everything else — so it is worth guarding where the change actually
     * happens, independently of how the request was routed.
     *
     * <p>Deliberately fails closed when there is no authenticated caller. No
     * bootstrap or migration path calls this service today — the initial
     * {@code admin} account is seeded in SQL by
     * {@code V2__seed_default_data.sql}, and LDAP logins write roles onto the
     * entity directly — so refusing an unauthenticated role grant costs nothing
     * and keeps a future internal caller from quietly becoming a way around
     * this.
     *
     * <p>Scoped to role changes on purpose. It is not a second copy of the
     * "must be admin to touch users" rule; duplicating that here would drift
     * from the chain and give a false sense that the chain is optional. Renaming
     * a user without touching roles grants nothing and stays the chain's call.
     */
    private static void requireAdminToChangeRoles(Set<String> currentRoles, Set<String> requestedRoles) {
        Set<String> effectiveRequested = requestedRoles == null ? Set.of() : requestedRoles;
        if (effectiveRequested.equals(currentRoles)) {
            return;
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean isAdmin = authentication != null
                && authentication.isAuthenticated()
                && authentication.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .anyMatch(ADMIN_AUTHORITY::equals);

        if (!isAdmin) {
            throw new AccessDeniedException("Only an administrator may assign roles");
        }
    }

    @Transactional
    public UserEntity createUser(
            String userId,
            String firstName,
            String lastName,
            String email,
            String password,
            Set<String> roles) {
        // Checked before the existence probe so that a caller who may not grant
        // roles cannot use the differing error responses to enumerate accounts.
        requireAdminToChangeRoles(Set.of(), roles);

        if (userJpaRepository.existsById(userId)) {
            throw new IllegalArgumentException("User already exists: " + userId);
        }

        UserEntity entity = new UserEntity();
        entity.setUserId(userId);
        entity.setFirstName(firstName);
        entity.setLastName(lastName);
        entity.setEmail(email);
        entity.setPasswordHash(passwordEncoder.encode(password));
        entity.setStatus(UserStatus.ACTIVE.name());
        entity.setRoles(roles);

        Instant now = Instant.now();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);

        return userJpaRepository.save(entity);
    }

    @Transactional
    public UserEntity updateUser(
            String userId, String firstName, String lastName, String email, String status, Set<String> roles) {
        UserEntity entity = userJpaRepository
                .findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        // Compares against what the account holds today, so this covers both
        // directions: granting a role, and stripping one off an administrator.
        requireAdminToChangeRoles(entity.getRoles(), roles);

        entity.setFirstName(firstName);
        entity.setLastName(lastName);
        entity.setEmail(email);
        entity.setStatus(status);
        entity.setRoles(roles);
        entity.setUpdatedAt(Instant.now());

        return userJpaRepository.save(entity);
    }

    @Transactional
    public void deleteUser(String userId) {
        if (!userJpaRepository.existsById(userId)) {
            throw new IllegalArgumentException("User not found: " + userId);
        }
        userJpaRepository.deleteById(userId);
    }

    @Transactional
    public void changePassword(String userId, String newPassword) {
        UserEntity entity = userJpaRepository
                .findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        entity.setPasswordHash(passwordEncoder.encode(newPassword));
        entity.setUpdatedAt(Instant.now());

        if (UserStatus.CHANGE_PASSWORD.name().equals(entity.getStatus())) {
            entity.setStatus(UserStatus.ACTIVE.name());
        }

        userJpaRepository.save(entity);
    }

    @Transactional
    public UserEntity updateProfile(String userId, String firstName, String lastName, String email) {
        UserEntity entity = userJpaRepository
                .findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        entity.setFirstName(firstName);
        entity.setLastName(lastName);
        entity.setEmail(email);
        entity.setUpdatedAt(Instant.now());

        return userJpaRepository.save(entity);
    }

    @Transactional
    public void changePasswordWithVerification(String userId, String currentPassword, String newPassword) {
        UserEntity entity = userJpaRepository
                .findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        if (!passwordEncoder.matches(currentPassword, entity.getPasswordHash())) {
            throw new IllegalArgumentException("Current password is incorrect");
        }

        entity.setPasswordHash(passwordEncoder.encode(newPassword));
        entity.setUpdatedAt(Instant.now());

        if (UserStatus.CHANGE_PASSWORD.name().equals(entity.getStatus())) {
            entity.setStatus(UserStatus.ACTIVE.name());
        }

        userJpaRepository.save(entity);
    }

    /**
     * Verify a user's local password without changing anything. Used to gate
     * access to sensitive account data (e.g. revealing the NuGet API key),
     * mirroring how Sonatype Nexus re-prompts for the password before showing
     * a user's token.
     *
     * <p>Returns {@code false} for users that have no local password hash (e.g.
     * LDAP-backed accounts), since their credentials cannot be verified here.
     */
    public boolean verifyPassword(String userId, String password) {
        if (password == null || password.isEmpty()) {
            return false;
        }
        return userJpaRepository
                .findById(userId)
                .map(UserEntity::getPasswordHash)
                .filter(hash -> hash != null && !hash.isBlank())
                .map(hash -> passwordEncoder.matches(password, hash))
                .orElse(false);
    }

    public List<UserEntity> findAll() {
        return userJpaRepository.findAll();
    }

    public Optional<UserEntity> findById(String userId) {
        return userJpaRepository.findById(userId);
    }
}
