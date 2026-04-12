package de.bsnsoft.megarepo.rest.controller;

import de.bsnsoft.megarepo.database.entity.UserEntity;
import de.bsnsoft.megarepo.rest.dto.security.ApiCreateUser;
import de.bsnsoft.megarepo.rest.dto.security.ApiUpdateProfile;
import de.bsnsoft.megarepo.rest.dto.security.ApiUser;
import de.bsnsoft.megarepo.security.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.security.Principal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/security/users")
public class SecurityUserController {

    private final UserService userService;

    public SecurityUserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public ResponseEntity<List<ApiUser>> list() {
        var users = userService.findAll().stream().map(this::toXO).toList();
        return ResponseEntity.ok(users);
    }

    @PostMapping
    public ResponseEntity<ApiUser> create(@Valid @RequestBody ApiCreateUser request) {
        var entity = userService.createUser(
                request.userId(),
                request.firstName(),
                request.lastName(),
                request.emailAddress(),
                request.password(),
                new HashSet<>(request.roles()));
        return ResponseEntity.created(URI.create("/api/v1/security/users/" + entity.getUserId()))
                .body(toXO(entity));
    }

    @PutMapping("/{userId}")
    public ResponseEntity<ApiUser> update(@PathVariable String userId, @Valid @RequestBody ApiCreateUser request) {
        var entity = userService.updateUser(
                userId,
                request.firstName(),
                request.lastName(),
                request.emailAddress(),
                request.status(),
                new HashSet<>(request.roles()));
        return ResponseEntity.ok(toXO(entity));
    }

    @DeleteMapping("/{userId}")
    public ResponseEntity<Void> delete(@PathVariable String userId) {
        userService.deleteUser(userId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{userId}/change-password")
    public ResponseEntity<Void> changePassword(@PathVariable String userId, @RequestBody Map<String, String> body) {
        String newPassword = body.get("password");
        if (newPassword == null || newPassword.isBlank()) {
            throw new IllegalArgumentException("Password is required");
        }
        if (newPassword.length() < 8) {
            throw new IllegalArgumentException("Password must be at least 8 characters");
        }
        if (newPassword.length() > 1000) {
            throw new IllegalArgumentException("Password must not exceed 1000 characters");
        }
        userService.changePassword(userId, newPassword);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public ResponseEntity<ApiUser> me(Principal principal) {
        var entity = userService
                .findById(principal.getName())
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        return ResponseEntity.ok(toXO(entity));
    }

    @PutMapping("/me")
    public ResponseEntity<ApiUser> updateMe(Principal principal, @Valid @RequestBody ApiUpdateProfile request) {
        var entity = userService.updateProfile(
                principal.getName(), request.firstName(), request.lastName(), request.emailAddress());
        return ResponseEntity.ok(toXO(entity));
    }

    @PutMapping("/me/change-password")
    public ResponseEntity<Void> changeMyPassword(Principal principal, @RequestBody Map<String, String> body) {
        String currentPassword = body.get("currentPassword");
        String newPassword = body.get("password");
        if (currentPassword == null || currentPassword.isBlank()) {
            throw new IllegalArgumentException("Current password is required");
        }
        if (newPassword == null || newPassword.isBlank()) {
            throw new IllegalArgumentException("New password is required");
        }
        if (newPassword.length() < 8) {
            throw new IllegalArgumentException("Password must be at least 8 characters");
        }
        if (newPassword.length() > 1000) {
            throw new IllegalArgumentException("Password must not exceed 1000 characters");
        }
        userService.changePasswordWithVerification(principal.getName(), currentPassword, newPassword);
        return ResponseEntity.noContent().build();
    }

    private ApiUser toXO(UserEntity entity) {
        return new ApiUser(
                entity.getUserId(),
                entity.getFirstName(),
                entity.getLastName(),
                entity.getEmail(),
                entity.getSource(),
                entity.getStatus(),
                false,
                List.copyOf(entity.getRoles()));
    }
}
