package de.bsnsoft.megarepo.security.service;

import de.bsnsoft.megarepo.core.security.UserStatus;
import de.bsnsoft.megarepo.database.entity.UserEntity;
import de.bsnsoft.megarepo.database.repository.UserJpaRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class UserService {

    private final UserJpaRepository userJpaRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserJpaRepository userJpaRepository, PasswordEncoder passwordEncoder) {
        this.userJpaRepository = userJpaRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public UserEntity createUser(
            String userId,
            String firstName,
            String lastName,
            String email,
            String password,
            Set<String> roles) {
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
