package de.bsnsoft.megarepo.it;

import de.bsnsoft.megarepo.app.MegaRepoApplication;
import de.bsnsoft.megarepo.database.entity.BlobStoreEntity;
import de.bsnsoft.megarepo.database.entity.RepositoryEntity;
import de.bsnsoft.megarepo.database.repository.AnonymousAccessJpaRepository;
import de.bsnsoft.megarepo.database.repository.AssetJpaRepository;
import de.bsnsoft.megarepo.database.repository.BlobStoreJpaRepository;
import de.bsnsoft.megarepo.database.repository.ComponentJpaRepository;
import de.bsnsoft.megarepo.database.repository.NegativeCacheJpaRepository;
import de.bsnsoft.megarepo.database.repository.RepositoryJpaRepository;
import de.bsnsoft.megarepo.database.repository.UserJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Base integration test that uses an external PostgreSQL instance (docker compose up db).
 * Run: docker compose up db -d   (before running integration tests)
 *
 * <p>If port 5432 is already taken on the developer machine, point the tests at a
 * different instance via {@code -Pmegarepo.it.db.url=jdbc:postgresql://localhost:55432/megarepo?stringtype=unspecified}
 * (forwarded to the test JVM as a system property).
 *
 * <h2>Shared, never-reset state</h2>
 *
 * <p>The Spring context and the database are shared across all integration-test classes,
 * and the database survives the JVM: it is external, and nothing drops or cleans it
 * between runs. A test that creates a fixed-name resource and asserts {@code 201 CREATED}
 * would therefore pass exactly once and conflict on every later run.
 *
 * <p>The suite's answer to that is <em>self-cleaning fixtures</em>, not a database wipe:
 * every class ensures what it needs with the find-or-create helpers below, and removes
 * what its own tests are about to create ({@link #deleteRepositoryIfExists(String)},
 * {@link #deleteBlobStoreIfExists(String)}, {@link #deleteUserIfExists(String)}) or
 * whatever they will write into a repository ({@link #purgeRepositoryContent(UUID)}).
 * Data seeded at boot by {@code FirstRunSetup} — default repositories, roles, the admin
 * user — is left alone, because {@code DefaultRepositoryFormatsIntegrationTest} asserts
 * against it and it is only ever created on a first run.
 */
@SpringBootTest(
        classes = MegaRepoApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class BaseIntegrationTest {

    /** Blob store seeded by {@code FirstRunSetup}; every fixture repository uses it. */
    protected static final String DEFAULT_BLOB_STORE = "default";

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

    @Autowired
    protected BlobStoreJpaRepository blobStoreJpaRepository;

    @Autowired
    protected RepositoryJpaRepository repositoryJpaRepository;

    @Autowired
    protected AssetJpaRepository assetJpaRepository;

    @Autowired
    protected ComponentJpaRepository componentJpaRepository;

    @Autowired
    protected NegativeCacheJpaRepository negativeCacheJpaRepository;

    @Autowired
    protected UserJpaRepository userJpaRepository;

    @Autowired
    protected AnonymousAccessJpaRepository anonymousAccessJpaRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    protected String baseUrl() {
        return "http://localhost:" + port;
    }

    protected String repositoryUrl(String repoName, String path) {
        return baseUrl() + "/repository/" + repoName + "/" + path;
    }

    // ------------------------------------------------------------------ suite baseline

    /**
     * Restores the one global setting a test flips: anonymous access.
     * {@code AuthChallengeIntegrationTest} disables it to observe the 401 challenge and
     * re-enables it afterwards, but a run that dies in between (or is killed) leaves it
     * disabled in the shared database — and then every unauthenticated repository request
     * in the suite gets a 401 on the <em>next</em> run. Superclass {@code @BeforeEach}
     * methods run before subclass ones, so that class can still disable it for itself.
     */
    @BeforeEach
    void restoreAnonymousAccessBaseline() {
        anonymousAccessJpaRepository.findById(1).ifPresent(settings -> {
            if (!settings.isEnabled()) {
                settings.setEnabled(true);
                anonymousAccessJpaRepository.save(settings);
            }
        });
    }

    // ------------------------------------------------------------------ fixture helpers

    /** Find-or-create the {@value #DEFAULT_BLOB_STORE} blob store (seed data creates it, but be safe). */
    protected void ensureDefaultBlobStore() {
        ensureFileBlobStore(DEFAULT_BLOB_STORE, "data/blobs/default");
    }

    /** Find-or-create a file blob store. Existing rows are left untouched. */
    protected void ensureFileBlobStore(String name, String path) {
        if (blobStoreJpaRepository.findById(name).isEmpty()) {
            var blobStore = new BlobStoreEntity();
            blobStore.setName(name);
            blobStore.setType("file");
            blobStore.setConfig(Map.of("path", path));
            blobStore.setCreatedAt(Instant.now());
            blobStore.setUpdatedAt(Instant.now());
            blobStoreJpaRepository.save(blobStore);
        }
    }

    /**
     * Find-or-create a repository fixture and bring it back to the requested shape —
     * the row survives previous JVM runs, but attributes such as a proxy's remote URL
     * may not still be valid for this one.
     *
     * @return the repository id, for {@link #purgeRepositoryContent(UUID)}
     */
    protected UUID ensureRepository(String name, String format, String type, Map<String, Object> attributes) {
        RepositoryEntity repo = repositoryJpaRepository.findByName(name).orElseGet(() -> {
            var created = new RepositoryEntity();
            created.setName(name);
            created.setCreatedAt(Instant.now());
            return created;
        });
        repo.setFormat(format);
        repo.setType(type);
        repo.setOnline(true);
        repo.setBlobStoreName(DEFAULT_BLOB_STORE);
        repo.setAttributes(attributes);
        repo.setUpdatedAt(Instant.now());
        return repositoryJpaRepository.save(repo).getId();
    }

    /**
     * Drops everything a previous run stored in this repository: assets, components and
     * negative-cache entries. Uploads and pushes then behave as they do against an empty
     * repository — {@code 201 CREATED} rather than a conflict with last run's leftovers.
     *
     * <p>Blobs themselves are deliberately left on disk: they are content-addressed and
     * re-referenced by the next upload, and the blob directory is a per-JVM temp dir anyway.
     */
    protected void purgeRepositoryContent(UUID repositoryId) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assetJpaRepository.deleteAll(assetJpaRepository.findAllByRepositoryId(repositoryId));
            componentJpaRepository.deleteAll(
                    componentJpaRepository.findByRepositoryId(repositoryId, Pageable.unpaged()).getContent());
            negativeCacheJpaRepository.deleteByRepositoryId(repositoryId);
        });
    }

    /** Removes a repository and its content if an earlier run left it behind. */
    protected void deleteRepositoryIfExists(String name) {
        repositoryJpaRepository.findByName(name).ifPresent(repo -> {
            purgeRepositoryContent(repo.getId());
            repositoryJpaRepository.delete(repo);
        });
    }

    /** Removes a blob store if an earlier run left it behind. */
    protected void deleteBlobStoreIfExists(String name) {
        blobStoreJpaRepository.findById(name).ifPresent(blobStoreJpaRepository::delete);
    }

    /** Removes a user (and its role assignments) if an earlier run left it behind. */
    protected void deleteUserIfExists(String userId) {
        userJpaRepository.findById(userId).ifPresent(userJpaRepository::delete);
    }
}
