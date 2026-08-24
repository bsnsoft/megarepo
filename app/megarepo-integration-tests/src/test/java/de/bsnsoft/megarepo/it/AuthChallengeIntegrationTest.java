package de.bsnsoft.megarepo.it;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the 401-challenge split between browser (SPA) requests and tooling
 * clients (Maven, npm, Docker), see osTicket #117649:
 *
 * <ul>
 *   <li>Web-UI requests ({@code /api/v1/**} or {@code X-Requested-With:
 *       XMLHttpRequest}) get a JSON 401 <b>without</b> {@code WWW-Authenticate}
 *       — otherwise browsers pop up their native Basic-Auth dialog instead of
 *       letting the SPA redirect to its login screen.</li>
 *   <li>Repository endpoints keep the {@code WWW-Authenticate: Basic} challenge
 *       that Maven/npm/pip/Docker need to know they must send credentials.</li>
 * </ul>
 */
class AuthChallengeIntegrationTest extends BaseIntegrationTest {

    private Boolean originalAnonymousEnabled;

    /**
     * Anonymous access is enabled by default and authenticates unauthenticated
     * repository requests as the anonymous user — they never reach the 401
     * entry point. Disable it so the challenge behavior can be observed, and
     * restore the original setting afterwards.
     *
     * <p>This is the only global setting the suite touches; the base class puts it
     * back to "enabled" before every test, so a run killed in the middle of this
     * class cannot leave the shared database in a state that breaks the next one.
     */
    @BeforeEach
    void disableAnonymousAccess() {
        anonymousAccessJpaRepository.findById(1).ifPresent(settings -> {
            originalAnonymousEnabled = settings.isEnabled();
            settings.setEnabled(false);
            anonymousAccessJpaRepository.save(settings);
        });
    }

    @AfterEach
    void restoreAnonymousAccess() {
        if (originalAnonymousEnabled != null) {
            anonymousAccessJpaRepository.findById(1).ifPresent(settings -> {
                settings.setEnabled(originalAnonymousEnabled);
                anonymousAccessJpaRepository.save(settings);
            });
        }
    }

    // ── Web-UI requests: no Basic challenge ─────────────────────────────

    @Test
    void uiApiPathWithoutAuth_returns401WithoutBasicChallenge() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                baseUrl() + "/api/v1/repositories", String.class);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertFalse(response.getHeaders().containsKey(HttpHeaders.WWW_AUTHENTICATE),
                "UI API 401 must not carry WWW-Authenticate (would trigger the browser's Basic-Auth popup)");
        assertNotNull(response.getHeaders().getContentType());
        assertTrue(response.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_JSON),
                "UI API 401 should have a JSON error body");
        assertTrue(response.getBody() != null && response.getBody().contains("\"status\":401"));
    }

    @Test
    void uiApiPathWithExpiredBearerToken_returns401WithoutBasicChallenge() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("invalid-or-expired-token");

        ResponseEntity<String> response = restTemplate.exchange(
                baseUrl() + "/api/v1/repositories",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertFalse(response.getHeaders().containsKey(HttpHeaders.WWW_AUTHENTICATE),
                "Expired-session 401 must not carry WWW-Authenticate");
    }

    @Test
    void ajaxMarkedRequestOnRepositoryPath_returns401WithoutBasicChallenge() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Requested-With", "XMLHttpRequest");

        ResponseEntity<String> response = restTemplate.exchange(
                repositoryUrl("maven-hosted", "com/example/app/1.0/app-1.0.jar"),
                HttpMethod.PUT,
                new HttpEntity<>("content", headers),
                String.class);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertFalse(response.getHeaders().containsKey(HttpHeaders.WWW_AUTHENTICATE),
                "AJAX-marked requests must not get a Basic challenge, even on repository paths");
    }

    // ── Tooling clients: Basic challenge stays ──────────────────────────

    @Test
    void repositoryUploadWithoutAuth_returns401WithBasicChallenge() {
        ResponseEntity<String> response = restTemplate.exchange(
                repositoryUrl("maven-hosted", "com/example/app/1.0/app-1.0.jar"),
                HttpMethod.PUT,
                new HttpEntity<>("content"),
                String.class);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        String challenge = response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE);
        assertNotNull(challenge, "Maven/npm clients need the Basic challenge to send credentials");
        assertTrue(challenge.startsWith("Basic"), "Expected Basic challenge but got: " + challenge);
    }

    @Test
    void repositoryUploadWithWrongCredentials_returns401WithBasicChallenge() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBasicAuth("mvn-deploy", "wrong-password");

        ResponseEntity<String> response = restTemplate.exchange(
                repositoryUrl("maven-hosted", "com/example/app/1.0/app-1.0.jar"),
                HttpMethod.PUT,
                new HttpEntity<>("content", headers),
                String.class);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        String challenge = response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE);
        assertNotNull(challenge, "Failed Basic auth must re-challenge tooling clients");
        assertTrue(challenge.startsWith("Basic"), "Expected Basic challenge but got: " + challenge);
    }

    @Test
    void dockerRegistryPathWithoutAuth_returns401WithBasicChallenge() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                baseUrl() + "/v2/_catalog", String.class);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        String challenge = response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE);
        assertNotNull(challenge, "Docker clients need a WWW-Authenticate challenge");
        assertTrue(challenge.startsWith("Basic"), "Expected Basic challenge but got: " + challenge);
    }
}
