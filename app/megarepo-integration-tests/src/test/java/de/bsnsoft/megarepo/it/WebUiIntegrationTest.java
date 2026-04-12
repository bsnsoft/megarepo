package de.bsnsoft.megarepo.it;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests that verify the web UI static resources are served correctly
 * by the Spring Boot backend. These tests ensure the SPA entry point and static
 * assets are accessible without authentication.
 */
class WebUiIntegrationTest extends BaseIntegrationTest {

    @Test
    void indexHtmlIsServed() {
        ResponseEntity<String> response = restTemplate.getForEntity(baseUrl() + "/", String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(), "Root path should return 200");
        assertNotNull(response.getBody(), "Response body should not be null");
        assertTrue(response.getBody().contains("MegaRepo"), "Page should contain 'MegaRepo'");
    }

    @Test
    void indexHtmlIsServedDirectly() {
        ResponseEntity<String> response = restTemplate.getForEntity(baseUrl() + "/index.html", String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(), "Direct index.html should return 200");
        assertNotNull(response.getBody(), "Response body should not be null");
        assertTrue(response.getBody().contains("MegaRepo"), "Page should contain 'MegaRepo'");
    }

    @Test
    void statusEndpointIsAccessibleWithoutAuth() {
        ResponseEntity<String> response = restTemplate.getForEntity(baseUrl() + "/api/v1/status", String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(), "Status endpoint should be accessible without auth");
    }

    @Test
    void actuatorHealthIsAccessible() {
        ResponseEntity<String> response = restTemplate.getForEntity(baseUrl() + "/actuator/health", String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(), "Actuator health should be accessible");
    }
}
