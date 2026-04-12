package de.bsnsoft.megarepo.security.service;

import de.bsnsoft.megarepo.core.security.UserStatus;
import de.bsnsoft.megarepo.database.entity.UserEntity;
import de.bsnsoft.megarepo.database.repository.UserJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserJpaRepository userJpaRepository;

    private PasswordEncoder passwordEncoder;
    private UserService userService;

    @BeforeEach
    void setUp() {
        passwordEncoder = new BCryptPasswordEncoder();
        userService = new UserService(userJpaRepository, passwordEncoder);
    }

    @Test
    void createUser_hashesPasswordWithBCrypt() {
        when(userJpaRepository.existsById("testuser")).thenReturn(false);
        when(userJpaRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        UserEntity created =
                userService.createUser("testuser", "Test", "User", "test@example.com", "plaintext", Set.of("nx-viewer"));

        assertNotNull(created.getPasswordHash());
        assertTrue(passwordEncoder.matches("plaintext", created.getPasswordHash()));
        assertEquals("testuser", created.getUserId());
        assertEquals("Test", created.getFirstName());
        assertEquals("User", created.getLastName());
        assertEquals("test@example.com", created.getEmail());
        assertEquals(Set.of("nx-viewer"), created.getRoles());
        assertEquals(UserStatus.ACTIVE.name(), created.getStatus());
    }

    @Test
    void createUser_throwsIfUserAlreadyExists() {
        when(userJpaRepository.existsById("existing")).thenReturn(true);

        assertThrows(
                IllegalArgumentException.class,
                () -> userService.createUser("existing", "E", "X", "e@x.com", "pass", Set.of()));
    }

    @Test
    void changePassword_updatesHashAndTimestamp() {
        UserEntity existing = new UserEntity();
        existing.setUserId("admin");
        existing.setPasswordHash(passwordEncoder.encode("oldpass"));
        existing.setStatus(UserStatus.ACTIVE.name());
        existing.setCreatedAt(Instant.now());
        existing.setUpdatedAt(Instant.now().minusSeconds(3600));

        when(userJpaRepository.findById("admin")).thenReturn(Optional.of(existing));
        when(userJpaRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        userService.changePassword("admin", "newpass");

        ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
        verify(userJpaRepository).save(captor.capture());

        UserEntity saved = captor.getValue();
        assertTrue(passwordEncoder.matches("newpass", saved.getPasswordHash()));
    }

    @Test
    void changePassword_resetsChangePasswordStatus() {
        UserEntity existing = new UserEntity();
        existing.setUserId("newuser");
        existing.setPasswordHash(passwordEncoder.encode("temp"));
        existing.setStatus(UserStatus.CHANGE_PASSWORD.name());
        existing.setCreatedAt(Instant.now());
        existing.setUpdatedAt(Instant.now());

        when(userJpaRepository.findById("newuser")).thenReturn(Optional.of(existing));
        when(userJpaRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        userService.changePassword("newuser", "permanent");

        ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
        verify(userJpaRepository).save(captor.capture());

        assertEquals(UserStatus.ACTIVE.name(), captor.getValue().getStatus());
    }

    @Test
    void findById_returnsUser() {
        UserEntity entity = new UserEntity();
        entity.setUserId("admin");
        when(userJpaRepository.findById("admin")).thenReturn(Optional.of(entity));

        Optional<UserEntity> result = userService.findById("admin");

        assertTrue(result.isPresent());
        assertEquals("admin", result.get().getUserId());
    }

    @Test
    void deleteUser_throwsIfNotFound() {
        when(userJpaRepository.existsById("ghost")).thenReturn(false);

        assertThrows(IllegalArgumentException.class, () -> userService.deleteUser("ghost"));
    }

    @Test
    void deleteUser_deletesExistingUser() {
        when(userJpaRepository.existsById("toDelete")).thenReturn(true);

        userService.deleteUser("toDelete");

        verify(userJpaRepository).deleteById("toDelete");
    }
}
