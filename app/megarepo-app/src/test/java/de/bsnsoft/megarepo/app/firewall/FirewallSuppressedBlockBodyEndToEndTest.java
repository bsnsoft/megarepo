package de.bsnsoft.megarepo.app.firewall;

import de.bsnsoft.megarepo.app.MegaRepoApplication;
import de.bsnsoft.megarepo.core.firewall.FirewallMode;
import de.bsnsoft.megarepo.database.entity.AdvisoryAffectedEntity;
import de.bsnsoft.megarepo.database.entity.AdvisoryEntity;
import de.bsnsoft.megarepo.database.entity.FirewallEnforcementSettingsEntity;
import de.bsnsoft.megarepo.database.entity.RepositoryEntity;
import de.bsnsoft.megarepo.database.repository.AdvisoryAffectedJpaRepository;
import de.bsnsoft.megarepo.database.repository.AdvisoryJpaRepository;
import de.bsnsoft.megarepo.database.repository.AssetJpaRepository;
import de.bsnsoft.megarepo.database.repository.ComponentJpaRepository;
import de.bsnsoft.megarepo.database.repository.FirewallEnforcementSettingsJpaRepository;
import de.bsnsoft.megarepo.database.repository.FirewallRepositoryConfigJpaRepository;
import de.bsnsoft.megarepo.database.repository.FirewallViolationJpaRepository;
import de.bsnsoft.megarepo.database.repository.RepositoryJpaRepository;
import de.bsnsoft.megarepo.repository.firewall.FirewallEnforcementSettingsService;
import de.bsnsoft.megarepo.rest.dto.firewall.FirewallEnforcementUpdateXO;
import de.bsnsoft.megarepo.rest.dto.firewall.FirewallRepositoryModeUpdateXO;
import de.bsnsoft.megarepo.rest.dto.firewall.FirewallRepositoryStateXO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The 403 of a deployment that has switched every optional part of it off
 * (osTicket #155155, wave B1).
 *
 * <h2>Why this is a class of its own</h2>
 *
 * The block body is configured by {@code megarepo.firewall.block.*}, which is
 * read once when the application context is built, so "what an operator can add"
 * and "what an operator can take away" are two deployments and cannot be two
 * methods of one test class. {@code FirewallSwitchEndToEndTest} is the first;
 * this is the second, and between them they pin down the property the customer's
 * requirement actually turns on: <em>no configuration may produce a 403 that
 * fails to say what was blocked and why.</em>
 *
 * <p>Both settings here are real operational choices rather than contrived ones.
 * An installation whose policy names are internal ("Q3-audit-finding-14") gains
 * nothing by printing them at a developer, and an installation where exemptions
 * are decided by a security team rather than requested self-service should not
 * offer a link to a form nobody may use — a link that leads to a refusal is worse
 * than no link. What must survive both is the part a build log is read for: which
 * component, from which repository, refused by which rule, over which advisory.
 */
@SpringBootTest(
        classes = MegaRepoApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FirewallSuppressedBlockBodyEndToEndTest {

    private static final String DB_USER = "megarepo";
    private static final String DB_PASSWORD = "megarepo";

    private static final String ADMIN = "admin";
    private static final String ADMIN_PASSWORD = "admin123";

    private static final String REPOSITORY = "maven-block-body-e2e";

    private static final String VERSION = "2.14.1";

    private static final String ADVISORY_ID = "GHSA-jfh8-c2jp-5v3q";

    private static final byte[] ARTIFACT = "PK pretend jar".getBytes(StandardCharsets.UTF_8);

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("megarepo")
                    .withUsername(DB_USER)
                    .withPassword(DB_PASSWORD)
                    .withUrlParam("stringtype", "unspecified")
                    .waitingFor(Wait.forListeningPort());

    private static final Path WORK_DIR = createWorkDir();

    static {
        POSTGRES.start();
        awaitJdbcReady();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> DB_USER);
        registry.add("spring.datasource.password", () -> DB_PASSWORD);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
        registry.add("spring.flyway.placeholder-replacement", () -> "false");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.open-in-view", () -> "false");
        registry.add("megarepo.security.jwt.secret", () -> "firewall-block-body-e2e-test-secret-key");
        registry.add("megarepo.data-directory", () -> directory(WORK_DIR.resolve("data")));
        registry.add("megarepo.blob-stores.default-path", () -> directory(WORK_DIR.resolve("blobs")));
        registry.add("megarepo.firewall.enforcement.settings-refresh-interval", () -> "10m");
        registry.add("megarepo.firewall.audit.reevaluation-interval", () -> "0s");

        // The subject of this class. Blank rather than absent for the template:
        // that is how a deployment says "there is no self-service request form",
        // and the body has to answer it by leaving the section out entirely
        // rather than by printing a link to nowhere.
        registry.add("megarepo.firewall.block.include-policy-name", () -> "false");
        registry.add("megarepo.firewall.block.include-advisory-links", () -> "false");
        registry.add("megarepo.firewall.block.exemption-request-url-template", () -> "");
    }

    @LocalServerPort private int port;

    @Autowired private TestRestTemplate rest;
    @Autowired private FirewallEnforcementSettingsService enforcementSettings;
    @Autowired private FirewallEnforcementSettingsJpaRepository enforcementRows;
    @Autowired private FirewallRepositoryConfigJpaRepository firewallConfigs;
    @Autowired private FirewallViolationJpaRepository violations;
    @Autowired private RepositoryJpaRepository repositories;
    @Autowired private ComponentJpaRepository components;
    @Autowired private AssetJpaRepository assets;
    @Autowired private AdvisoryJpaRepository advisories;
    @Autowired private AdvisoryAffectedJpaRepository affected;

    private UUID repositoryId;

    @BeforeEach
    void freshInstallation() {
        violations.deleteAllInBatch();
        firewallConfigs.deleteAllInBatch();
        assets.deleteAllInBatch();
        components.deleteAllInBatch();
        affected.deleteAllInBatch();
        advisories.deleteAllInBatch();

        FirewallEnforcementSettingsEntity pristine = new FirewallEnforcementSettingsEntity();
        pristine.setId(FirewallEnforcementSettingsEntity.SINGLETON_ID);
        pristine.setConfigured(false);
        pristine.setEnabled(false);
        pristine.setEnforcingSince(null);
        pristine.setUpdatedAt(Instant.now());
        pristine.setUpdatedBy(null);
        enforcementRows.saveAndFlush(pristine);
        enforcementSettings.refresh();

        repositoryId = givenHostedMavenRepository();
        givenCriticalAdvisory();
    }

    @Test
    @DisplayName("with the policy name, the advisory links and the exemption link switched "
            + "off, the 403 still says what was blocked and why")
    void theSuppressedBlockBodyStillExplainsItself() {
        // Published while the repository is only observing: as of B1 an upload
        // into an armed, quarantining hosted repository is judged by the same
        // policy, so publishing a denied component afterwards would be refused
        // and this fixture would never store the artifact it asserts on.
        assertThat(setRepositoryMode(FirewallMode.AUDIT, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(setEnforcement().getStatusCode()).isEqualTo(HttpStatus.OK);
        upload();
        assertThat(setRepositoryMode(FirewallMode.QUARANTINE, "QUARANTINE " + REPOSITORY).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        ResponseEntity<String> refused = download(MediaType.ALL_VALUE);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(refused.getBody())
                .as("the part no configuration may remove: a developer staring at a failed "
                        + "build has to be able to decide 'upgrade the dependency' or 'ask the "
                        + "administrator' without opening anything else")
                .contains("MegaRepo repository firewall: this download was blocked.")
                .contains(REPOSITORY)
                .contains("log4j-core@" + VERSION)
                .contains("CVSS_THRESHOLD")
                .contains(ADVISORY_ID)
                .contains("ask your MegaRepo administrator");

        assertThat(refused.getBody())
                .as("and the parts this deployment switched off are gone — not blank lines, "
                        + "not an empty label, not a link to nowhere")
                .doesNotContain("Policy     :")
                .doesNotContain("https://github.com/advisories/")
                .doesNotContain("To ask for an exemption")
                .doesNotContain("/api/v1/firewall/exemptions");
        assertThat(refused.getHeaders().getFirst("X-MegaRepo-Firewall-Exemption-Request"))
                .as("suppressed in the headers too: a client that only reads those must not be "
                        + "sent somewhere the operator has closed")
                .isNull();
        assertThat(refused.getHeaders().getFirst("X-MegaRepo-Firewall"))
                .as("what is not configurable is that the refusal identifies itself")
                .isEqualTo("blocked");
        assertThat(refused.getHeaders().getFirst("X-MegaRepo-Firewall-Rule"))
                .contains("CVSS_THRESHOLD");

        ResponseEntity<String> asJson = download(MediaType.APPLICATION_JSON_VALUE);
        assertThat(asJson.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(asJson.getBody())
                .as("npm prints body.error verbatim, and it has to carry the same sentence")
                .contains("\"code\":\"FIREWALL_BLOCKED\"")
                .contains("\"error\":\"MegaRepo firewall blocked")
                .contains(ADVISORY_ID);
        assertThat(asJson.getBody())
                .as("a field that is switched off is absent rather than null: a client "
                        + "rendering 'Policy: null' would be worse than one rendering nothing")
                .doesNotContain("\"policy\"")
                .doesNotContain("\"advisoryLinks\"")
                .doesNotContain("\"exemptionRequest\"")
                .doesNotContain("\"contact\"");
    }

    // ── The administration API, over HTTP, as an nx-admin ────────────────────

    private ResponseEntity<String> setEnforcement() {
        return admin().exchange(
                url("/api/v1/admin/firewall/enforcement"),
                HttpMethod.PUT,
                json(new FirewallEnforcementUpdateXO(true, "ENABLE ENFORCEMENT")),
                String.class);
    }

    private ResponseEntity<FirewallRepositoryStateXO> setRepositoryMode(
            FirewallMode mode, String confirmation) {
        return admin().exchange(
                url("/api/v1/admin/firewall/repositories/" + repositoryId),
                HttpMethod.PUT,
                json(new FirewallRepositoryModeUpdateXO(mode, confirmation)),
                FirewallRepositoryStateXO.class);
    }

    // ── The repository API, over HTTP ────────────────────────────────────────

    private void upload() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        ResponseEntity<String> response = admin().exchange(
                url("/repository/" + REPOSITORY + "/" + path()),
                HttpMethod.PUT,
                new HttpEntity<>(ARTIFACT, headers),
                String.class);
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("upload failed: %s", response.getBody())
                .isTrue();
    }

    private ResponseEntity<String> download(String accept) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, accept);
        return rest.exchange(
                url("/repository/" + REPOSITORY + "/" + path()),
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class);
    }

    private static String path() {
        return "org/apache/logging/log4j/log4j-core/%s/log4j-core-%s.jar".formatted(VERSION, VERSION);
    }

    // ── Fixture ─────────────────────────────────────────────────────────────

    private UUID givenHostedMavenRepository() {
        return repositories.findByName(REPOSITORY)
                .map(RepositoryEntity::getId)
                .orElseGet(this::createHostedMavenRepository);
    }

    private UUID createHostedMavenRepository() {
        RepositoryEntity repository = new RepositoryEntity();
        repository.setName(REPOSITORY);
        repository.setFormat("maven2");
        repository.setType("HOSTED");
        repository.setOnline(true);
        repository.setBlobStoreName("default");
        repository.setCreatedAt(Instant.now());
        repository.setUpdatedAt(Instant.now());
        return repositories.saveAndFlush(repository).getId();
    }

    private void givenCriticalAdvisory() {
        AdvisoryEntity advisory = new AdvisoryEntity();
        advisory.setId(ADVISORY_ID);
        advisory.setSource("GHSA");
        advisory.setSummary("Remote code execution");
        advisory.setSeverity("CRITICAL");
        advisory.setCvssScore(10.0);
        advisory.setPublished(Instant.parse("2021-12-10T00:00:00Z"));
        advisory.setModified(Instant.parse("2021-12-10T00:00:00Z"));
        advisory.setCreatedAt(Instant.now());
        advisory.setUpdatedAt(Instant.now());
        advisories.saveAndFlush(advisory);

        AdvisoryAffectedEntity range = new AdvisoryAffectedEntity();
        range.setAdvisoryId(ADVISORY_ID);
        range.setPurlType("maven");
        range.setPurlNamespace("org.apache.logging.log4j");
        range.setPurlName("log4j-core");
        range.setVersionRange(">=2.0-beta9, <2.15.0");
        range.setIntroduced("2.0-beta9");
        range.setFixed("2.15.0");
        affected.saveAndFlush(range);
    }

    // ── Plumbing ────────────────────────────────────────────────────────────

    private TestRestTemplate admin() {
        return rest.withBasicAuth(ADMIN, ADMIN_PASSWORD);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private static HttpEntity<Object> json(Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private static void sleep() {
        try {
            Thread.sleep(25);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for PostgreSQL", e);
        }
    }

    private static Path createWorkDir() {
        try {
            return Files.createTempDirectory("megarepo-firewall-block-body-e2e");
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create the test working directory", e);
        }
    }

    private static String directory(Path path) {
        try {
            Files.createDirectories(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create " + path, e);
        }
        return path.toString();
    }

    private static void awaitJdbcReady() {
        long deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
        SQLException last = null;
        while (System.nanoTime() < deadline) {
            try (Connection ignored =
                    DriverManager.getConnection(POSTGRES.getJdbcUrl(), DB_USER, DB_PASSWORD)) {
                return;
            } catch (SQLException e) {
                last = e;
                sleep();
            }
        }
        throw new IllegalStateException("PostgreSQL container never became reachable", last);
    }
}
