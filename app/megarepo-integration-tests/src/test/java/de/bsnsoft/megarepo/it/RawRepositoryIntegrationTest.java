package de.bsnsoft.megarepo.it;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Integration tests for the raw repository format.
 *
 * <p>These tests verify the full end-to-end pipeline:
 * HTTP request -> RepositoryRouter -> RawRequestHandler -> BlobStore -> Database
 *
 * <p>Note: Since other agents are building the router and raw plugin in parallel,
 * these tests define WHAT should work. They may not pass until the other modules
 * are wired up, but they must compile.
 */
class RawRepositoryIntegrationTest extends BaseIntegrationTest {

    private static final String REPO_NAME = "raw-integration-test";

    /**
     * Find-or-create the fixture repository and empty it, so the uploads below are the
     * first ones at their paths — the same file uploaded by an earlier run against this
     * shared database would make {@code uploadAndDownloadFile} assert against an
     * overwrite instead of a create.
     */
    @BeforeEach
    void setUp() {
        ensureDefaultBlobStore();
        purgeRepositoryContent(
                ensureRepository(REPO_NAME, "raw", "HOSTED", Map.of("writePolicy", "ALLOW")));
    }

    @Test
    void uploadAndDownloadFile() {
        String path = "path/to/file.txt";
        String content = "Hello, World!";

        // Upload
        HttpHeaders uploadHeaders = new HttpHeaders();
        uploadHeaders.setContentType(MediaType.TEXT_PLAIN);
        HttpEntity<String> uploadRequest = new HttpEntity<>(content, uploadHeaders);

        ResponseEntity<String> uploadResponse = restTemplate.exchange(
                repositoryUrl(REPO_NAME, path),
                HttpMethod.PUT,
                uploadRequest,
                String.class);

        assertEquals(HttpStatus.CREATED, uploadResponse.getStatusCode(),
                "PUT should return 201 Created");

        // Download
        ResponseEntity<String> downloadResponse = restTemplate.getForEntity(
                repositoryUrl(REPO_NAME, path), String.class);

        assertEquals(HttpStatus.OK, downloadResponse.getStatusCode(),
                "GET should return 200 OK");
        assertEquals(content, downloadResponse.getBody(),
                "Downloaded content should match uploaded content");
        assertNotNull(downloadResponse.getHeaders().getContentType(),
                "Content-Type header should be set");
    }

    @Test
    void downloadNonExistentFileReturns404() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                repositoryUrl(REPO_NAME, "does-not-exist.txt"), String.class);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode(),
                "GET for non-existent file should return 404");
    }

    @Test
    void uploadOverwriteWithAllowPolicy() {
        String path = "overwrite-test/file.txt";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);

        // First upload
        HttpEntity<String> firstUpload = new HttpEntity<>("version 1", headers);
        ResponseEntity<String> firstResponse = restTemplate.exchange(
                repositoryUrl(REPO_NAME, path),
                HttpMethod.PUT,
                firstUpload,
                String.class);

        assertEquals(HttpStatus.CREATED, firstResponse.getStatusCode(),
                "First PUT should return 201 Created");

        // Second upload (overwrite)
        HttpEntity<String> secondUpload = new HttpEntity<>("version 2", headers);
        ResponseEntity<String> secondResponse = restTemplate.exchange(
                repositoryUrl(REPO_NAME, path),
                HttpMethod.PUT,
                secondUpload,
                String.class);

        // ALLOW policy should permit overwrite (either 200 or 201 is acceptable)
        int statusCode = secondResponse.getStatusCode().value();
        assertTrue(statusCode == 200 || statusCode == 201,
                "Second PUT with ALLOW write policy should succeed, got: " + statusCode);

        // Verify the content is the second version
        ResponseEntity<String> downloadResponse = restTemplate.getForEntity(
                repositoryUrl(REPO_NAME, path), String.class);

        assertEquals(HttpStatus.OK, downloadResponse.getStatusCode());
        assertEquals("version 2", downloadResponse.getBody(),
                "Content should be the second version after overwrite");
    }

    @Test
    void deleteFile() {
        String path = "delete-test/file.txt";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);

        // Upload a file
        HttpEntity<String> uploadRequest = new HttpEntity<>("to be deleted", headers);
        restTemplate.exchange(
                repositoryUrl(REPO_NAME, path),
                HttpMethod.PUT,
                uploadRequest,
                String.class);

        // Delete it
        ResponseEntity<String> deleteResponse = restTemplate.exchange(
                repositoryUrl(REPO_NAME, path),
                HttpMethod.DELETE,
                null,
                String.class);

        int deleteStatus = deleteResponse.getStatusCode().value();
        assertTrue(deleteStatus == 200 || deleteStatus == 204,
                "DELETE should return 200 or 204, got: " + deleteStatus);

        // Verify it is gone
        ResponseEntity<String> getResponse = restTemplate.getForEntity(
                repositoryUrl(REPO_NAME, path), String.class);

        assertEquals(HttpStatus.NOT_FOUND, getResponse.getStatusCode(),
                "GET after DELETE should return 404");
    }

    @Test
    void repositoryNotFoundReturns404() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                repositoryUrl("nonexistent-repo", "file.txt"), String.class);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode(),
                "Request to non-existent repository should return 404");
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
