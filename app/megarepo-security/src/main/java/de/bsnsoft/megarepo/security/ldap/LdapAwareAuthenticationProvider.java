package de.bsnsoft.megarepo.security.ldap;

import de.bsnsoft.megarepo.core.security.UserStatus;
import de.bsnsoft.megarepo.database.entity.UserEntity;
import de.bsnsoft.megarepo.database.repository.UserJpaRepository;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Authentication provider that first tries local DB auth, then falls back to LDAP.
 * When LDAP succeeds, the user is synced to the local database with source="ldap".
 */
@Component
public class LdapAwareAuthenticationProvider implements AuthenticationProvider {

    private final UserJpaRepository userJpaRepository;
    private final PasswordEncoder passwordEncoder;
    private final LdapAuthenticationService ldapAuthenticationService;

    public LdapAwareAuthenticationProvider(
            UserJpaRepository userJpaRepository,
            PasswordEncoder passwordEncoder,
            LdapAuthenticationService ldapAuthenticationService) {
        this.userJpaRepository = userJpaRepository;
        this.passwordEncoder = passwordEncoder;
        this.ldapAuthenticationService = ldapAuthenticationService;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        String username = authentication.getName();
        String password = authentication.getCredentials().toString();

        // 1. Try local DB authentication first
        Optional<UserEntity> localUser = userJpaRepository.findById(username);
        if (localUser.isPresent()) {
            UserEntity entity = localUser.get();
            UserStatus status = UserStatus.valueOf(entity.getStatus());

            if (status == UserStatus.LOCKED) {
                throw new BadCredentialsException("User account is locked: " + username);
            }

            if ((status == UserStatus.ACTIVE || status == UserStatus.CHANGE_PASSWORD)
                    && !"ldap".equals(entity.getSource())
                    && passwordEncoder.matches(password, entity.getPasswordHash())) {
                return buildAuthentication(entity);
            }

            // If user source is "ldap", skip local password check and fall through to LDAP
            if (!"ldap".equals(entity.getSource())) {
                // Local user with wrong password - still try LDAP in case they were migrated
            }
        }

        // 2. Try LDAP authentication as fallback
        Optional<LdapUserInfo> ldapResult = ldapAuthenticationService.authenticate(username, password);
        if (ldapResult.isPresent()) {
            UserEntity syncedUser = syncLdapUser(ldapResult.get());
            return buildAuthentication(syncedUser);
        }

        throw new BadCredentialsException("Invalid credentials");
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }

    private Authentication buildAuthentication(UserEntity entity) {
        var authorities = entity.getRoles().stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
        return new UsernamePasswordAuthenticationToken(entity.getUserId(), null, authorities);
    }

    UserEntity syncLdapUser(LdapUserInfo ldapUser) {
        Optional<UserEntity> existing = userJpaRepository.findById(ldapUser.username());
        Instant now = Instant.now();

        UserEntity entity;
        if (existing.isPresent()) {
            entity = existing.get();
            entity.setEmail(ldapUser.email());
            updateNameFromDisplayName(entity, ldapUser.displayName());
            entity.setSource("ldap");
            entity.setStatus(UserStatus.ACTIVE.name());
            if (!ldapUser.groups().isEmpty()) {
                entity.setRoles(new HashSet<>(ldapUser.groups()));
            }
            entity.setUpdatedAt(now);
        } else {
            entity = new UserEntity();
            entity.setUserId(ldapUser.username());
            entity.setEmail(ldapUser.email() != null && !ldapUser.email().isEmpty()
                    ? ldapUser.email()
                    : ldapUser.username() + "@ldap");
            updateNameFromDisplayName(entity, ldapUser.displayName());
            entity.setPasswordHash(passwordEncoder.encode(java.util.UUID.randomUUID().toString()));
            entity.setSource("ldap");
            entity.setStatus(UserStatus.ACTIVE.name());
            entity.setRoles(
                    ldapUser.groups().isEmpty() ? Set.of("nx-viewer") : new HashSet<>(ldapUser.groups()));
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);
        }

        return userJpaRepository.save(entity);
    }

    private void updateNameFromDisplayName(UserEntity entity, String displayName) {
        if (displayName != null && !displayName.isEmpty()) {
            String[] parts = displayName.split("\\s+", 2);
            entity.setFirstName(parts[0]);
            entity.setLastName(parts.length > 1 ? parts[1] : "");
        } else {
            entity.setFirstName(entity.getUserId() != null ? entity.getUserId() : "LDAP");
            entity.setLastName("User");
        }
    }
}
