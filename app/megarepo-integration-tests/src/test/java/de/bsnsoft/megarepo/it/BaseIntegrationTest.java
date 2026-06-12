package de.bsnsoft.megarepo.it;

import de.bsnsoft.megarepo.app.MegaRepoApplication;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Base integration test that uses an external PostgreSQL instance (docker compose up db).
 * Run: docker compose up db -d   (before running integration tests)
 *
 * <p>If port 5432 is already taken on the developer machine, point the tests at a
 * different instance via {@code -Pmegarepo.it.db.url=jdbc:postgresql://localhost:55432/megarepo?stringtype=unspecified}
 * (forwarded to the test JVM as a system property).
 */
@SpringBootTest(
        classes = MegaRepoApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class BaseIntegrationTest {

    /**
     * Context-scoped working directory. Deliberately NOT a JUnit {@code @TempDir}:
     * static temp dirs are deleted after each test <em>class</em>, while the Spring
     * context (and its data/blob-store paths) is cached and shared across all
     * integration-test classes — the health indicators would report DOWN for every
     * class after the first one. Created once per JVM instead.
     */
    static final Path tempDir = createJvmScopedTempDir();

    private static Path createJvmScopedTempDir() {
        try {
            return Files.createTempDirectory("megarepo-it");
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create integration-test temp directory", e);
        }
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> System.getProperty(
                        "megarepo.it.db.url",
                        "jdbc:postgresql://localhost:5432/megarepo?stringtype=unspecified"));
        registry.add("spring.datasource.username", () -> "megarepo");
        registry.add("spring.datasource.password", () -> "megarepo");
        registry.add("spring.flyway.clean-disabled", () -> "false");
        // Create the directories up front — the disk-space and blob-store health
        // indicators report DOWN (=> 503 on /actuator/health) when they are missing.
        registry.add("megarepo.data-directory", () -> createdDirectory(tempDir.resolve("data")));
        registry.add("megarepo.blob-stores.default-path", () -> createdDirectory(tempDir.resolve("blobs")));
    }

    private static String createdDirectory(Path path) {
        try {
            Files.createDirectories(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create test directory " + path, e);
        }
        return path.toString();
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
