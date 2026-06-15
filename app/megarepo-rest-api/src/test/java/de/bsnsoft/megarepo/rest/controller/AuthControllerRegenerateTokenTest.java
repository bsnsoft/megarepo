package de.bsnsoft.megarepo.rest.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.bsnsoft.megarepo.rest.advice.GlobalExceptionHandler;
import de.bsnsoft.megarepo.rest.dto.security.TokenResponse;
import de.bsnsoft.megarepo.security.auth.JwtTokenProvider;
import de.bsnsoft.megarepo.security.auth.ratelimit.LoginRateLimiter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests the "Reset API key" endpoint: an authenticated caller exchanges their
 * security context for a fresh MegaRepo JWT (the NuGet/npm/Maven API key).
 */
class AuthControllerRegenerateTokenTest {

    private MockMvc mockMvc;
    private JwtTokenProvider jwtTokenProvider;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        jwtTokenProvider = new JwtTokenProvider(
                "test-secret-that-is-long-enough-to-sign-hs256",
                Duration.ofHours(12),
                Duration.ofDays(7));
        var controller = new AuthController(
                mock(AuthenticationProvider.class), jwtTokenProvider, mock(LoginRateLimiter.class));
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void regenerateToken_issuesFreshValidTokenForAuthenticatedUser() throws Exception {
        var auth = new UsernamePasswordAuthenticationToken(
                "alice", null,
                List.of(new SimpleGrantedAuthority("ROLE_nx-admin"),
                        new SimpleGrantedAuthority("ROLE_nx-deploy")));
        SecurityContextHolder.getContext().setAuthentication(auth);

        var result = mockMvc.perform(post("/api/v1/security/auth/regenerate-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andReturn();

        TokenResponse response = objectMapper.readValue(
                result.getResponse().getContentAsString(), TokenResponse.class);

        // The issued token must be a valid MegaRepo JWT carrying the caller's identity + roles.
        assertTrue(jwtTokenProvider.validateToken(response.token()));
        assertEquals("alice", jwtTokenProvider.getUserIdFromToken(response.token()));
        assertEquals(Set.of("nx-admin", "nx-deploy"),
                jwtTokenProvider.getRolesFromToken(response.token()));
    }

    @Test
    void regenerateToken_unauthenticated_returns401() throws Exception {
        // No authentication in the security context.
        mockMvc.perform(post("/api/v1/security/auth/regenerate-token"))
                .andExpect(status().isUnauthorized());
    }
}
