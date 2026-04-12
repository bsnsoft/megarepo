package de.bsnsoft.megarepo.security.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtTokenProviderTest {

    private JwtTokenProvider jwtTokenProvider;

    @BeforeEach
    void setUp() {
        jwtTokenProvider = new JwtTokenProvider(
                "test-secret-key-for-unit-tests-minimum-32-bytes", Duration.ofMinutes(30), Duration.ofDays(7));
    }

    @Test
    void generateAccessToken_createsValidToken() {
        String token = jwtTokenProvider.generateAccessToken("admin", Set.of("nx-admin"));

        assertNotNull(token);
        assertTrue(jwtTokenProvider.validateToken(token));
    }

    @Test
    void getUserIdFromToken_returnsCorrectUserId() {
        String token = jwtTokenProvider.generateAccessToken("testuser", Set.of("nx-viewer"));

        String userId = jwtTokenProvider.getUserIdFromToken(token);

        assertEquals("testuser", userId);
    }

    @Test
    void getRolesFromToken_returnsCorrectRoles() {
        Set<String> roles = Set.of("nx-admin", "nx-deployer");
        String token = jwtTokenProvider.generateAccessToken("admin", roles);

        Set<String> extractedRoles = jwtTokenProvider.getRolesFromToken(token);

        assertEquals(roles, extractedRoles);
    }

    @Test
    void generateRefreshToken_createsValidToken() {
        String token = jwtTokenProvider.generateRefreshToken("admin");

        assertNotNull(token);
        assertTrue(jwtTokenProvider.validateToken(token));
        assertEquals("admin", jwtTokenProvider.getUserIdFromToken(token));
    }

    @Test
    void validateToken_returnsFalseForInvalidToken() {
        assertFalse(jwtTokenProvider.validateToken("invalid.token.here"));
    }

    @Test
    void validateToken_returnsFalseForNullToken() {
        assertFalse(jwtTokenProvider.validateToken(null));
    }

    @Test
    void validateToken_returnsFalseForExpiredToken() {
        JwtTokenProvider shortLived = new JwtTokenProvider(
                "test-secret-key-for-unit-tests-minimum-32-bytes", Duration.ofMillis(-1), Duration.ofMillis(-1));

        String token = shortLived.generateAccessToken("admin", Set.of("nx-admin"));

        assertFalse(jwtTokenProvider.validateToken(token));
    }

    @Test
    void getRolesFromToken_returnsEmptySetForRefreshToken() {
        String token = jwtTokenProvider.generateRefreshToken("admin");

        Set<String> roles = jwtTokenProvider.getRolesFromToken(token);

        assertTrue(roles.isEmpty());
    }

    @Test
    void generateAccessToken_withShortSecret_padsToMinimumLength() {
        JwtTokenProvider shortSecret = new JwtTokenProvider("short", Duration.ofMinutes(30), Duration.ofDays(7));

        String token = shortSecret.generateAccessToken("admin", Set.of("nx-admin"));

        assertNotNull(token);
        assertTrue(shortSecret.validateToken(token));
    }
}
