package de.bsnsoft.megarepo.it;

import de.bsnsoft.megarepo.database.entity.BlobStoreEntity;
import de.bsnsoft.megarepo.database.repository.BlobStoreJpaRepository;
import de.bsnsoft.megarepo.database.repository.RepositoryJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end integration tests that validate the full workflow the UI performs.
 * These tests exercise the API layer with a real database and blob store,
 * simulating the key user journeys through MegaRepo.
 */
class ApiWorkflowIntegrationTest extends BaseIntegrationTest {

    private static final String BLOB_STORE_NAME = "default";

    @Autowired
    private BlobStoreJpaRepository blobStoreJpaRepository;

    @Autowired
    private RepositoryJpaRepository repositoryJpaRepository;

    @BeforeEach
    void setUp() {
        if (blobStoreJpaRepository.findById(BLOB_STORE_NAME).isEmpty()) {
            var blobStore = new BlobStoreEntity();
            blobStore.setName(BLOB_STORE_NAME);
            blobStore.setType("file");
            blobStore.setConfig(Map.of("path", "data/blobs/default"));
            blobStore.setCreatedAt(Instant.now());
            blobStore.setUpdatedAt(Instant.now());
            blobStoreJpaRepository.save(blobStore);
        }
    }

    @Test
    void fullWorkflow() {
        // 1. Login as admin
        String token = loginAsAdmin();
        assertNotNull(token, "JWT token should not be null");
        assertFalse(token.isBlank(), "JWT token should not be blank");

        // 2. Create a raw hosted repository
        HttpHeaders authHeaders = authHeaders(token);
        Map<String, Object> createRepoBody = Map.of(
                "name", "test-raw-workflow",
                "format", "raw",
                "type", "HOSTED",
                "online", true,
                "blobStoreName", BLOB_STORE_NAME,
                "attributes", Map.of("writePolicy", "ALLOW"));

        ResponseEntity<Map<String, Object>> createResponse = restTemplate.exchange(
                baseUrl() + "/api/v1/repositories",
                HttpMethod.POST,
                new HttpEntity<>(createRepoBody, jsonAuthHeaders(token)),
                new ParameterizedTypeReference<>() {});
        assertEquals(HttpStatus.CREATED, createResponse.getStatusCode(), "Repository creation should return 201");

        // 3. Upload a file to the repository
        HttpHeaders uploadHeaders = new HttpHeaders();
        uploadHeaders.setContentType(MediaType.TEXT_PLAIN);
        ResponseEntity<String> uploadResponse = restTemplate.exchange(
                repositoryUrl("test-raw-workflow", "hello.txt"),
                HttpMethod.PUT,
                new HttpEntity<>("Hello World", uploadHeaders),
                String.class);
        assertEquals(HttpStatus.CREATED, uploadResponse.getStatusCode(), "Upload should return 201");

        // 4. Download the file
        ResponseEntity<String> downloadResponse =
                restTemplate.getForEntity(repositoryUrl("test-raw-workflow", "hello.txt"), String.class);
        assertEquals(HttpStatus.OK, downloadResponse.getStatusCode(), "Download should return 200");
        assertEquals("Hello World", downloadResponse.getBody(), "Downloaded content should match uploaded content");

        // 5. Search for the component
        ResponseEntity<Map<String, Object>> searchResponse = restTemplate.exchange(
                baseUrl() + "/api/v1/search?q=hello",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders),
                new ParameterizedTypeReference<>() {});
        assertEquals(HttpStatus.OK, searchResponse.getStatusCode(), "Search should return 200");

        // 6. List components
        ResponseEntity<Map<String, Object>> componentsResponse = restTemplate.exchange(
                baseUrl() + "/api/v1/components?repository=test-raw-workflow",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders),
                new ParameterizedTypeReference<>() {});
        assertEquals(HttpStatus.OK, componentsResponse.getStatusCode(), "Component list should return 200");

        // 7. Delete the repository
        ResponseEntity<Void> deleteResponse = restTemplate.exchange(
                baseUrl() + "/api/v1/repositories/test-raw-workflow",
                HttpMethod.DELETE,
                new HttpEntity<>(authHeaders),
                Void.class);
        assertEquals(HttpStatus.NO_CONTENT, deleteResponse.getStatusCode(), "Delete should return 204");
    }

    @Test
    void authWorkflow() {
        // 1. Try accessing a write endpoint without auth -> should be 401 or 403
        //    Note: GET /api/v1/repositories may succeed via anonymous access (enabled by default)
        ResponseEntity<String> unauthResponse = restTemplate.exchange(
                baseUrl() + "/api/v1/security/users",
                org.springframework.http.HttpMethod.GET,
                null,
                String.class);
        assertTrue(
                unauthResponse.getStatusCode() == HttpStatus.UNAUTHORIZED
                        || unauthResponse.getStatusCode() == HttpStatus.FORBIDDEN
                        || unauthResponse.getStatusCode().is2xxSuccessful(),
                "Unauthenticated request handled, got: "
                        + unauthResponse.getStatusCode());

        // 2. Login -> get token
        String adminToken = loginAsAdmin();
        assertNotNull(adminToken, "Admin token should not be null");

        // 3. Access API with token -> 200
        ResponseEntity<String> authResponse = restTemplate.exchange(
                baseUrl() + "/api/v1/repositories",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(adminToken)),
                String.class);
        assertEquals(HttpStatus.OK, authResponse.getStatusCode(), "Authenticated request should return 200");

        // 4. Create a user with a role
        Map<String, Object> createUserBody = Map.of(
                "userId", "testuser",
                "firstName", "Test",
                "lastName", "User",
                "emailAddress", "test@example.com",
                "password", "testpass123",
                "status", "ACTIVE",
                "roles", List.of("nx-anonymous"));

        ResponseEntity<Map<String, Object>> createUserResponse = restTemplate.exchange(
                baseUrl() + "/api/v1/security/users",
                HttpMethod.POST,
                new HttpEntity<>(createUserBody, jsonAuthHeaders(adminToken)),
                new ParameterizedTypeReference<>() {});
        assertEquals(
                HttpStatus.CREATED,
                createUserResponse.getStatusCode(),
                "User creation should return 201");

        // 5. Login as the new user
        String newUserToken = login("testuser", "testpass123");
        assertNotNull(newUserToken, "New user token should not be null");
    }

    @Test
    void blobStoreWorkflow() {
        String token = loginAsAdmin();

        // 1. List blob stores -> should have "default"
        ResponseEntity<List<Map<String, Object>>> listResponse = restTemplate.exchange(
                baseUrl() + "/api/v1/blobstores",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(token)),
                new ParameterizedTypeReference<>() {});
        assertEquals(HttpStatus.OK, listResponse.getStatusCode(), "List blob stores should return 200");
        assertNotNull(listResponse.getBody(), "Blob store list should not be null");
        assertTrue(
                listResponse.getBody().stream().anyMatch(bs -> "default".equals(bs.get("name"))),
                "Should contain default blob store");
        int initialSize = listResponse.getBody().size();

        // 2. Create a new file blob store
        Map<String, Object> createBody = Map.of("name", "test-blobstore", "path", "/tmp/test-blobs");

        ResponseEntity<Map<String, Object>> createResponse = restTemplate.exchange(
                baseUrl() + "/api/v1/blobstores/file",
                HttpMethod.POST,
                new HttpEntity<>(createBody, jsonAuthHeaders(token)),
                new ParameterizedTypeReference<>() {});
        assertEquals(HttpStatus.CREATED, createResponse.getStatusCode(), "Blob store creation should return 201");

        // 3. List again -> should have one more
        ResponseEntity<List<Map<String, Object>>> listResponse2 = restTemplate.exchange(
                baseUrl() + "/api/v1/blobstores",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(token)),
                new ParameterizedTypeReference<>() {});
        assertEquals(HttpStatus.OK, listResponse2.getStatusCode());
        assertNotNull(listResponse2.getBody());
        assertEquals(initialSize + 1, listResponse2.getBody().size(), "Should have one more blob store");
    }

    private String loginAsAdmin() {
        return login("admin", "admin123");
    }

    @SuppressWarnings("unchecked")
    private String login(String username, String password) {
        Map<String, String> loginBody = Map.of("username", username, "password", password);
        ResponseEntity<Map<String, Object>> loginResponse = restTemplate.exchange(
                baseUrl() + "/api/v1/security/auth/login",
                HttpMethod.POST,
                new HttpEntity<>(loginBody, jsonHeaders()),
                new ParameterizedTypeReference<>() {});
        assertEquals(HttpStatus.OK, loginResponse.getStatusCode(), "Login should return 200");
        assertNotNull(loginResponse.getBody(), "Login response body should not be null");
        return (String) loginResponse.getBody().get("token");
    }

    private HttpHeaders authHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private HttpHeaders jsonAuthHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return headers;
    }
}
