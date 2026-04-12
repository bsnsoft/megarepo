package de.bsnsoft.megarepo.it;

import de.bsnsoft.megarepo.app.MegaRepoApplication;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;

/**
 * Base integration test that uses an external PostgreSQL instance (docker compose up db).
 * Run: docker compose up db -d   (before running integration tests)
 */
@SpringBootTest(
        classes = MegaRepoApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class BaseIntegrationTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:postgresql://localhost:5432/megarepo?stringtype=unspecified");
        registry.add("spring.datasource.username", () -> "megarepo");
        registry.add("spring.datasource.password", () -> "megarepo");
        registry.add("spring.flyway.clean-disabled", () -> "false");
        registry.add("megarepo.data-directory", () -> tempDir.resolve("data").toString());
        registry.add("megarepo.blob-stores.default-path", () -> tempDir.resolve("blobs").toString());
    }

    @LocalServerPort
    protected int port;

    @Autowired
    protected TestRestTemplate restTemplate;

    protected String baseUrl() {
        return "http://localhost:" + port;
    }

    protected String repositoryUrl(String repoName, String path) {
        return baseUrl() + "/repository/" + repoName + "/" + path;
    }
}
