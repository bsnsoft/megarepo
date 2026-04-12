package de.bsnsoft.megarepo.rest.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.bsnsoft.megarepo.database.entity.UserEntity;
import de.bsnsoft.megarepo.rest.advice.GlobalExceptionHandler;
import de.bsnsoft.megarepo.rest.dto.security.ApiCreateUser;
import de.bsnsoft.megarepo.security.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class SecurityUserControllerTest {

    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private UserService userService;

    @BeforeEach
    void setUp() {
        var controller = new SecurityUserController(userService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void listUsers() throws Exception {
        var user1 = createUserEntity("admin", "Admin", "User", "admin@example.com");
        var user2 = createUserEntity("dev", "Dev", "User", "dev@example.com");

        when(userService.findAll()).thenReturn(List.of(user1, user2));

        mockMvc.perform(get("/api/v1/security/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].userId").value("admin"))
                .andExpect(jsonPath("$[0].firstName").value("Admin"))
                .andExpect(jsonPath("$[0].emailAddress").value("admin@example.com"))
                .andExpect(jsonPath("$[1].userId").value("dev"));
    }

    @Test
    void createUser_returns201() throws Exception {
        var request = new ApiCreateUser(
                "newuser", "New", "User", "new@example.com", "secret123", "ACTIVE", List.of("nx-admin"));

        var savedEntity = createUserEntity("newuser", "New", "User", "new@example.com");
        savedEntity.setRoles(Set.of("nx-admin"));
        when(userService.createUser(anyString(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(savedEntity);

        mockMvc.perform(post("/api/v1/security/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/security/users/newuser"))
                .andExpect(jsonPath("$.userId").value("newuser"))
                .andExpect(jsonPath("$.firstName").value("New"))
                .andExpect(jsonPath("$.lastName").value("User"))
                .andExpect(jsonPath("$.emailAddress").value("new@example.com"));

        verify(userService).createUser(
                eq("newuser"), eq("New"), eq("User"), eq("new@example.com"), eq("secret123"), any());
    }

    @Test
    void deleteUser_returns204() throws Exception {
        doNothing().when(userService).deleteUser("admin");

        mockMvc.perform(delete("/api/v1/security/users/admin"))
                .andExpect(status().isNoContent());

        verify(userService).deleteUser("admin");
    }

    @Test
    void deleteUser_notFound_returns400() throws Exception {
        doThrow(new IllegalArgumentException("User not found: nonexistent"))
                .when(userService).deleteUser("nonexistent");

        mockMvc.perform(delete("/api/v1/security/users/nonexistent"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("User not found: nonexistent"));
    }

    @Test
    void changePassword() throws Exception {
        doNothing().when(userService).changePassword("admin", "newpassword");

        mockMvc.perform(put("/api/v1/security/users/admin/change-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("password", "newpassword"))))
                .andExpect(status().isNoContent());

        verify(userService).changePassword("admin", "newpassword");
    }

    @Test
    void changePassword_userNotFound_returns400() throws Exception {
        doThrow(new IllegalArgumentException("User not found: nonexistent"))
                .when(userService).changePassword(eq("nonexistent"), anyString());

        mockMvc.perform(put("/api/v1/security/users/nonexistent/change-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("password", "newpassword"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    private static UserEntity createUserEntity(String userId, String firstName, String lastName, String email) {
        var entity = new UserEntity();
        entity.setUserId(userId);
        entity.setFirstName(firstName);
        entity.setLastName(lastName);
        entity.setEmail(email);
        entity.setPasswordHash("hashed");
        entity.setStatus("ACTIVE");
        entity.setSource("default");
        entity.setRoles(Set.of());
        entity.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        entity.setUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        return entity;
    }
}
