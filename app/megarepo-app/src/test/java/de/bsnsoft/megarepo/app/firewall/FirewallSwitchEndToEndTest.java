package de.bsnsoft.megarepo.app.firewall;

import de.bsnsoft.megarepo.app.MegaRepoApplication;
import de.bsnsoft.megarepo.core.firewall.FirewallMode;
import de.bsnsoft.megarepo.database.entity.AdvisoryAffectedEntity;
import de.bsnsoft.megarepo.database.entity.AdvisoryEntity;
import de.bsnsoft.megarepo.database.entity.FirewallEnforcementSettingsEntity;
import de.bsnsoft.megarepo.database.entity.FirewallViolationEntity;
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
import de.bsnsoft.megarepo.rest.dto.firewall.FirewallEnforcementXO;
import de.bsnsoft.megarepo.rest.dto.firewall.FirewallOverviewXO;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one thing neither side's own tests can show: that flipping the switch in
 * the administration API actually changes what a download does.
 *
 * <h2>Why this test exists</h2>
 *
 * Phase 2 was built as two halves. {@code FirewallEnforcementDatabaseTest} proves
 * the enforcement half against a real schema, but arms it by calling
 * {@link FirewallEnforcementSettingsService#save} directly.
 * {@code FirewallAdminControllerTest} proves the administration half, but against
 * a mocked repository — nothing downstream of it exists in that context. Both are
 * green while the seam between them is broken, and the seam is the entire
 * customer-facing promise: <em>the operator flips the switch, and from then on it
 * blocks.</em> Anything that goes wrong between the write and the read — a second
 * source of truth, a cache that is never invalidated, a flag the writer forgets
 * to set — is invisible to both and visible to the customer immediately.
 *
 * <p>So everything here is real: the whole {@link MegaRepoApplication} on a real
 * port, a real PostgreSQL with the real migrations, a real artifact uploaded
 * through the repository API, real HTTP calls to
 * {@code /api/v1/admin/firewall/**} as an {@code nx-admin}, and a real
 * {@code GET /repository/**} as an anonymous client. No mock, no stub, no
 * service called from the test to arm anything.
 *
 * <h2>Why the master switch is cached for ten minutes here</h2>
 *
 * The switch is read on every download and therefore held in memory, normally
 * for {@code settings-refresh-interval} (10s in production). A test that arms
 * the firewall and then waits for that interval to lapse proves nothing about
 * the API — it proves that a cache expires, which it would do whether or not
 * anyone flipped a switch, and it would leave the operator staring at a
 * "Blocking" badge on an instance that is still serving. The interval is
 * therefore pushed far out of reach of the test's runtime: every assertion below
 * is on the <em>very next request</em> after the API call, so the only way this
 * class can pass is if writing the switch through the API makes it effective at
 * once.
 *
 * <h2>The grandfathering rule is asserted, not worked around</h2>
 *
 * The customer's second constraint is that components already stored when the
 * switch was flipped are audited but never blocked, so that arming cannot break
 * a build that depends on something already cached. That rule is load-bearing
 * here: the artifact uploaded <em>before</em> arming keeps being served
 * afterwards, and it is the one uploaded <em>after</em> arming that is refused.
 * Both are asserted, because an operator who reads "it blocks now" and sees a
 * cached artifact still being served needs that to be the documented behaviour
 * rather than a surprise.
 */
@SpringBootTest(
        classes = MegaRepoApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FirewallSwitchEndToEndTest {

    private static final String DB_USER = "megarepo";
    private static final String DB_PASSWORD = "megarepo";

    private static final String ADMIN = "admin";
    private static final String ADMIN_PASSWORD = "admin123";

    private static final String REPOSITORY = "maven-firewall-e2e";

    /**
     * Uploaded before the switch is armed — the "already in the repository" case.
     */
    private static final String CACHED = "2.14.1";

    /**
     * Uploaded after the switch is armed — the case the promise is about. One
     * version per test method: the recorder writes off the request thread, so a
     * row from the previous method can still land during this one, and a shared
     * version would let the wrong row satisfy an assertion.
     */
    private static final String FRESH = "2.14.2";

    private static final String FRESH_FOR_MODE_CHANGE = "2.14.3";

    private static final String ADVISORY_ID = "GHSA-jfh8-c2jp-5v3q";

    private static final byte[] ARTIFACT = "PK pretend jar".getBytes(StandardCharsets.UTF_8);

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("megarepo")
                    .withUsername(DB_USER)
                    .withPassword(DB_PASSWORD)
                    // Matches the project's own JDBC URLs: without it every JSONB
                    // write fails, because the driver would send String payloads
                    // as varchar instead of letting PostgreSQL infer jsonb.
                    .withUrlParam("stringtype", "unspecified")
                    .waitingFor(Wait.forListeningPort());

    /** Blob store and data directory, created once per JVM like the context that uses them. */
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
        registry.add("megarepo.security.jwt.secret", () -> "firewall-end-to-end-test-secret-key");
        registry.add("megarepo.data-directory", () -> directory(WORK_DIR.resolve("data")));
        registry.add("megarepo.blob-stores.default-path", () -> directory(WORK_DIR.resolve("blobs")));
        // See the class comment: far longer than this class can possibly run, so
        // that a passing assertion can only mean the API pushed the change and
        // never that a cache happened to expire in between.
        registry.add("megarepo.firewall.enforcement.settings-refresh-interval", () -> "10m");
        // The observation path's per-node shortcut would otherwise skip the
        // second look at a path it has already seen, and every step below
        // downloads the same two paths repeatedly.
        registry.add("megarepo.firewall.audit.reevaluation-interval", () -> "0s");
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

    /**
     * Back to a freshly migrated installation.
     *
     * <p>This is the only place that touches the switch other than through the
     * API — {@code enforcing_since} is a watermark that is deliberately never
     * reset at runtime, so a second test method would otherwise inherit the
     * first one's. Resetting it here is fixture, not the thing under test; every
     * assertion below still arms and disarms through HTTP only.
     */
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
        // Re-reads the pristine row and restarts the ten-minute cache window, so
        // the test starts in the adversarial state: a warm cache that says "off".
        enforcementSettings.refresh();

        repositoryId = givenHostedMavenRepository();
        givenCriticalAdvisory();
    }

    @Test
    @DisplayName("arming through the API refuses the very next download, and disarming serves it again")
    void theMasterSwitchTakesEffectImmediately() {
        givenQuarantineThroughTheApi();
        upload(CACHED);

        // ── 1. Switch off: QUARANTINE is observed, never enforced ────────────
        assertThat(enforcementThroughTheApi().enabled())
                .as("a freshly migrated installation ships disarmed")
                .isFalse();
        assertThat(effectiveState())
                .as("the API must not call an unarmed instance protected")
                .isEqualTo("QUARANTINE_NOT_ENFORCED");
        assertThat(download(CACHED).getStatusCode())
                .as("nothing blocks while the master switch is off, whatever the mode says")
                .isEqualTo(HttpStatus.OK);

        // ── 1b. Arming is guarded, and a refused arming really does not arm ──
        assertThat(setEnforcement(true, null).getStatusCode())
                .as("arming without the confirmation phrase is a 400")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(download(CACHED).getStatusCode())
                .as("a rejected arming request must leave the instance disarmed")
                .isEqualTo(HttpStatus.OK);

        // ── 2. Arm, through the endpoint the Web UI calls ────────────────────
        ResponseEntity<FirewallEnforcementXO> armed =
                setEnforcement(true, "ENABLE ENFORCEMENT", FirewallEnforcementXO.class);
        assertThat(armed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(armed.getBody()).isNotNull();
        assertThat(armed.getBody().enabled()).isTrue();
        assertThat(armed.getBody().updatedBy()).isEqualTo(ADMIN);
        assertThat(effectiveState()).isEqualTo("BLOCKING");

        // ── 3. The very next download of newly stored content is refused ─────
        upload(FRESH);
        ResponseEntity<String> refused = download(FRESH);

        assertThat(refused.getStatusCode())
                .as("this is the promise: the operator flips the switch and it blocks — "
                        + "on the next request, not after a cache lapses")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(refused.getHeaders().getFirst("X-MegaRepo-Firewall")).isEqualTo("blocked");
        assertThat(refused.getHeaders().getFirst("X-MegaRepo-Firewall-Rule"))
                .contains("CVSS_THRESHOLD");
        assertThat(refused.getHeaders().getFirst("X-MegaRepo-Firewall-Advisories"))
                .contains(ADVISORY_ID);
        assertThat(refused.getBody())
                .as("a developer reads this in a build log and has to be able to act on it")
                .contains("MegaRepo repository firewall")
                .contains(REPOSITORY)
                .contains("log4j-core@" + FRESH)
                .contains("CVSS_THRESHOLD")
                .contains(ADVISORY_ID);

        // npm prints body.error verbatim, so the JSON shape has to carry the same
        // sentence — a client that shows only one of the two must still be told why.
        ResponseEntity<String> refusedAsJson = download(FRESH, MediaType.APPLICATION_JSON_VALUE);
        assertThat(refusedAsJson.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(refusedAsJson.getBody())
                .contains("\"code\":\"FIREWALL_BLOCKED\"")
                .contains("\"error\":\"MegaRepo firewall blocked")
                .contains(ADVISORY_ID);

        FirewallViolationEntity blocked = awaitEnforcementViolationFor(FRESH);
        assertThat(blocked.getRequestContext())
                .as("the row has to agree with what the client was told")
                .containsEntry("enforced", true)
                .containsEntry("blocked", true)
                .containsEntry("preExisting", false);

        // ── 3b. What was already stored when the switch was flipped is served ─
        assertThat(download(CACHED).getStatusCode())
                .as("arming must not break a build that depends on something already cached")
                .isEqualTo(HttpStatus.OK);
        assertThat(awaitEnforcementViolationFor(CACHED).getRequestContext())
                .as("recorded as a finding, but not as a block")
                .containsEntry("preExisting", true)
                .containsEntry("blocked", false);

        // ── 4. Disarm, and the same download goes through again ──────────────
        assertThat(setEnforcement(false, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(effectiveState()).isEqualTo("QUARANTINE_NOT_ENFORCED");
        assertThat(download(FRESH).getStatusCode())
                .as("the operator turning enforcement off is usually doing it while builds "
                        + "are failing, so it cannot take a cache interval either")
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("moving a repository out of QUARANTINE through the API serves it again at once")
    void theRepositoryModeTakesEffectImmediately() {
        givenQuarantineThroughTheApi();
        assertThat(setEnforcement(true, "ENABLE ENFORCEMENT").getStatusCode()).isEqualTo(HttpStatus.OK);

        upload(FRESH_FOR_MODE_CHANGE);
        assertThat(download(FRESH_FOR_MODE_CHANGE).getStatusCode())
                .as("armed instance, QUARANTINE repository, critical advisory")
                .isEqualTo(HttpStatus.FORBIDDEN);
        awaitEnforcementViolationFor(FRESH_FOR_MODE_CHANGE);

        ResponseEntity<FirewallRepositoryStateXO> audited = setRepositoryMode(FirewallMode.AUDIT, null);

        assertThat(audited.getStatusCode())
                .as("leaving QUARANTINE needs no confirmation phrase — only arming does")
                .isEqualTo(HttpStatus.OK);
        assertThat(audited.getBody()).isNotNull();
        assertThat(audited.getBody().effectiveState().name()).isEqualTo("OBSERVING");

        assertThat(download(FRESH_FOR_MODE_CHANGE).getStatusCode())
                .as("the master switch is still on; this repository simply no longer asks "
                        + "to be enforced, and that has to apply to the next request")
                .isEqualTo(HttpStatus.OK);
    }

    // ── The administration API, over HTTP, as an nx-admin ────────────────────

    private ResponseEntity<String> setEnforcement(Boolean enabled, String confirmation) {
        return setEnforcement(enabled, confirmation, String.class);
    }

    private <T> ResponseEntity<T> setEnforcement(Boolean enabled, String confirmation, Class<T> type) {
        return admin().exchange(
                url("/api/v1/admin/firewall/enforcement"),
                HttpMethod.PUT,
                json(new FirewallEnforcementUpdateXO(enabled, confirmation)),
                type);
    }

    private ResponseEntity<FirewallRepositoryStateXO> setRepositoryMode(
            FirewallMode mode, String confirmation) {
        return admin().exchange(
                url("/api/v1/admin/firewall/repositories/" + repositoryId),
                HttpMethod.PUT,
                json(new FirewallRepositoryModeUpdateXO(mode, confirmation)),
                FirewallRepositoryStateXO.class);
    }

    private FirewallEnforcementXO enforcementThroughTheApi() {
        ResponseEntity<FirewallEnforcementXO> response = admin().getForEntity(
                url("/api/v1/admin/firewall/enforcement"), FirewallEnforcementXO.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    /** What {@code GET /status} says this repository is actually doing. */
    private String effectiveState() {
        ResponseEntity<FirewallOverviewXO> response =
                admin().getForEntity(url("/api/v1/admin/firewall/status"), FirewallOverviewXO.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        return response.getBody().repositories().stream()
                .filter(repository -> REPOSITORY.equals(repository.repositoryName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(REPOSITORY + " missing from the firewall status"))
                .effectiveState()
                .name();
    }

    /** Arms the repository itself, through the API, phrase included. */
    private void givenQuarantineThroughTheApi() {
        ResponseEntity<FirewallRepositoryStateXO> response =
                setRepositoryMode(FirewallMode.QUARANTINE, "QUARANTINE " + REPOSITORY);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().mode()).isEqualTo(FirewallMode.QUARANTINE);
    }

    // ── The repository API, over HTTP ────────────────────────────────────────

    /** Uploads the artifact, which is what creates its component and asset rows. */
    private void upload(String version) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        ResponseEntity<String> response = admin().exchange(
                url("/repository/" + REPOSITORY + "/" + path(version)),
                HttpMethod.PUT,
                new HttpEntity<>(ARTIFACT, headers),
                String.class);
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("upload of %s failed: %s", version, response.getBody())
                .isTrue();
    }

    private static String path(String version) {
        return "org/apache/logging/log4j/log4j-core/%s/log4j-core-%s.jar".formatted(version, version);
    }

    /**
     * Downloads anonymously, the way Maven's transport does: no JSON in
     * {@code Accept}, so a refusal comes back as the plain-text body a build log
     * can show.
     */
    private ResponseEntity<String> download(String version) {
        return download(version, MediaType.ALL_VALUE);
    }

    private ResponseEntity<String> download(String version, String accept) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, accept);
        return rest.exchange(
                url("/repository/" + REPOSITORY + "/" + path(version)),
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class);
    }

    // ── Fixture ─────────────────────────────────────────────────────────────

    /**
     * The repository row outlives the per-method purge on purpose. The violation
     * recorder writes off the request thread, so a row from the previous method
     * can still be in flight; deleting the repository under it turns that into a
     * foreign-key error in the log for no gain.
     */
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

    /** One advisory, CVSS 10.0, covering both versions used here. */
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

    /**
     * The row the <em>enforcement</em> path wrote for this version.
     *
     * <p>Two paths write to {@code firewall_violation} and both can have a row
     * for the same component: the observation path records "these advisories name
     * it" ({@code phase=audit}) and the enforcement path records what the policy
     * concluded ({@code phase=enforcement}). Only the second one says whether the
     * download was denied, so an assertion about a verdict must not be allowed to
     * settle for the first.
     *
     * <p>Polled because the verdict is given before the row is written — the
     * client must not wait for the audit trail.
     */
    private FirewallViolationEntity awaitEnforcementViolationFor(String version) {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            List<FirewallViolationEntity> rows = enforcementRowsFor(version);
            if (!rows.isEmpty()) {
                return rows.get(0);
            }
            sleep();
        }
        throw new AssertionError("The enforcement path recorded nothing for version " + version
                + "; rows: " + violations.findAll().stream()
                        .map(row -> row.getPurl() + " " + row.getRequestContext().get("phase"))
                        .toList());
    }

    private List<FirewallViolationEntity> enforcementRowsFor(String version) {
        return violations.findAll().stream()
                .filter(row -> row.getPurl() != null && row.getPurl().endsWith("@" + version))
                .filter(row -> row.getRequestContext() != null
                        && "enforcement".equals(row.getRequestContext().get("phase")))
                .toList();
    }

    private static void sleep() {
        try {
            Thread.sleep(25);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the violation row", e);
        }
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

    private static Path createWorkDir() {
        try {
            return Files.createTempDirectory("megarepo-firewall-e2e");
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

    /**
     * The container reports readiness from its own log, but where containers live
     * in a VM the host port forward can lag behind that. Poll for a real JDBC
     * connection rather than let the race decide.
     */
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
