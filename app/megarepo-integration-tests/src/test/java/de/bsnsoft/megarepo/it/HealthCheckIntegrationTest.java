package de.bsnsoft.megarepo.it;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HealthCheckIntegrationTest extends BaseIntegrationTest {

    @Test
    void actuatorHealthEndpointReturns200() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                baseUrl() + "/actuator/health", String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains("\"status\""));
    }
}
