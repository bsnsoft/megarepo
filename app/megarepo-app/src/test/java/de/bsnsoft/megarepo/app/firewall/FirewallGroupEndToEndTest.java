package de.bsnsoft.megarepo.app.firewall;

import de.bsnsoft.megarepo.app.MegaRepoApplication;
import de.bsnsoft.megarepo.core.firewall.FirewallMode;
import de.bsnsoft.megarepo.database.entity.AdvisoryAffectedEntity;
import de.bsnsoft.megarepo.database.entity.AdvisoryEntity;
import de.bsnsoft.megarepo.database.entity.FirewallEnforcementSettingsEntity;
import de.bsnsoft.megarepo.database.entity.FirewallViolationEntity;
import de.bsnsoft.megarepo.database.entity.GroupMemberEntity;
import de.bsnsoft.megarepo.database.entity.RepositoryEntity;
import de.bsnsoft.megarepo.database.repository.AdvisoryAffectedJpaRepository;
import de.bsnsoft.megarepo.database.repository.AdvisoryJpaRepository;
import de.bsnsoft.megarepo.database.repository.AssetJpaRepository;
import de.bsnsoft.megarepo.database.repository.ComponentJpaRepository;
import de.bsnsoft.megarepo.database.repository.FirewallEnforcementSettingsJpaRepository;
import de.bsnsoft.megarepo.database.repository.FirewallRepositoryConfigJpaRepository;
import de.bsnsoft.megarepo.database.repository.FirewallViolationJpaRepository;
import de.bsnsoft.megarepo.database.repository.GroupMemberJpaRepository;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The firewall, exercised the way this customer actually consumes artifacts:
 * through a <b>group</b> repository (osTicket #155155).
 *
 * <h2>Why a second end-to-end test</h2>
 *
 * {@code FirewallSwitchEndToEndTest} proves the switch works for a download
 * addressed straight to the repository that holds the artifact. Every consumer
 * at this customer is pointed at a group instead — one URL in one
 * {@code settings.xml}, members swapped behind it — and a group resolves its
 * artifacts out of those members. The enforcement hook used to be handed the
 * <em>group's</em> id, which owns no assets and no
 * {@code firewall_repository_config}: the lookup found nothing, the verdict was
 * "not enforcing", and the artifact went out. An armed instance with a
 * quarantined proxy served every blocked component to anyone who asked the group
 * for it, and both existing test suites stayed green throughout, because neither
 * ever routed a download through one.
 *
 * <p>So this class asserts the property that was missing rather than the code
 * that now provides it: <em>a download through a group is treated exactly as the
 * same download addressed to the member that resolves it.</em> Same 403, same
 * body, same violation row, same grandfathering — and, in the other direction,
 * the same silence when the master switch is off.
 *
 * <h2>The fallthrough question</h2>
 *
 * A group tries its members in order and stops at the first that has the
 * artifact. {@link #theBlockingMemberDecidesAndTheSearchStops()} pins down what
 * happens when a <em>later</em> member also has the same file: the download is
 * refused. Continuing the search would turn every group into a bypass — put a
 * clean hosted repository behind a quarantined proxy and the policy evaporates —
 * which is the one outcome this feature exists to prevent.
 *
 * <p>Everything here is real, on the same terms as the sibling test: the whole
 * application on a real port, real migrations, real HTTP for both the
 * administration API and the download.
 */
@SpringBootTest(
        classes = MegaRepoApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FirewallGroupEndToEndTest {

    private static final String DB_USER = "megarepo";
    private static final String DB_PASSWORD = "megarepo";

    private static final String ADMIN = "admin";
    private static final String ADMIN_PASSWORD = "admin123";

    /** The repository that actually holds the artifacts and carries the mode. */
    private static final String MEMBER = "maven-group-e2e-member";

    /**
     * A second member with no firewall configuration at all, holding the same
     * path. Exists only so that "the first hit decides" is a real assertion and
     * not a vacuous one.
     */
    private static final String SPARE = "maven-group-e2e-spare";

    /** What every consumer points at. */
    private static final String GROUP = "maven-group-e2e";

    /** One version per test method — the recorder writes off the request thread. */
    private static final String BLOCKED = "2.14.4";
    private static final String AUDITED = "2.14.5";
    private static final String SHADOWED = "2.14.6";
    private static final String GRANDFATHERED = "2.14.7";

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
        registry.add("megarepo.security.jwt.secret", () -> "firewall-group-end-to-end-test-secret");
        registry.add("megarepo.data-directory", () -> directory(WORK_DIR.resolve("data")));
        registry.add("megarepo.blob-stores.default-path", () -> directory(WORK_DIR.resolve("blobs")));
        // Far longer than this class can run: a passing assertion must mean the
        // API pushed the change, never that a cache happened to lapse.
        registry.add("megarepo.firewall.enforcement.settings-refresh-interval", () -> "10m");
        // The same path is downloaded repeatedly below; the per-node shortcut
        // would otherwise skip the second look.
        registry.add("megarepo.firewall.audit.reevaluation-interval", () -> "0s");
    }

    @LocalServerPort private int port;

    @Autowired private TestRestTemplate rest;
    @Autowired private FirewallEnforcementSettingsService enforcementSettings;
    @Autowired private FirewallEnforcementSettingsJpaRepository enforcementRows;
    @Autowired private FirewallRepositoryConfigJpaRepository firewallConfigs;
    @Autowired private FirewallViolationJpaRepository violations;
    @Autowired private RepositoryJpaRepository repositories;
    @Autowired private GroupMemberJpaRepository groupMembers;
    @Autowired private ComponentJpaRepository components;
    @Autowired private AssetJpaRepository assets;
    @Autowired private AdvisoryJpaRepository advisories;
    @Autowired private AdvisoryAffectedJpaRepository affected;

    private UUID memberId;
    private UUID spareId;
    private UUID groupId;

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

        memberId = givenHostedMavenRepository(MEMBER);
        spareId = givenHostedMavenRepository(SPARE);
        groupId = givenGroup();
        givenCriticalAdvisory();
    }

    @Test
    @DisplayName("a blocked artifact stays blocked when it is pulled through the group")
    void theGroupIsNotAWayAround() {
        armRepository(MEMBER, memberId);
        arm();

        upload(MEMBER, BLOCKED);

        // ── The download every consumer here actually makes ──────────────────
        ResponseEntity<String> refused = downloadFrom(GROUP, BLOCKED);

        assertThat(refused.getStatusCode())
                .as("the group resolves this artifact out of a quarantined member, so it is "
                        + "the same download as one addressed to that member — and it is refused")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(refused.getHeaders().getFirst("X-MegaRepo-Firewall")).isEqualTo("blocked");
        assertThat(refused.getHeaders().getFirst("X-MegaRepo-Firewall-Rule"))
                .contains("CVSS_THRESHOLD");
        assertThat(refused.getHeaders().getFirst("X-MegaRepo-Firewall-Advisories"))
                .contains(ADVISORY_ID);
        assertThat(refused.getBody())
                .as("the developer read this in a build log after asking for '%s' and has never "
                        + "heard of '%s', so both names have to be in it", GROUP, MEMBER)
                .contains("MegaRepo repository firewall")
                .contains(GROUP)
                .contains(MEMBER)
                .contains("log4j-core@" + BLOCKED)
                .contains("CVSS_THRESHOLD")
                .contains(ADVISORY_ID);

        ResponseEntity<String> refusedAsJson = downloadFrom(GROUP, BLOCKED, MediaType.APPLICATION_JSON_VALUE);
        assertThat(refusedAsJson.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(refusedAsJson.getBody())
                .contains("\"code\":\"FIREWALL_BLOCKED\"")
                .contains("\"repository\":\"" + MEMBER + "\"")
                .contains("\"viaRepository\":\"" + GROUP + "\"")
                .contains(ADVISORY_ID);

        // ── The row is filed where an operator can act on it ─────────────────
        FirewallViolationEntity blocked = awaitEnforcementViolationFor(BLOCKED);
        assertThat(blocked.getRepositoryId())
                .as("attributed to the repository that holds the component, not to the "
                        + "routing table in front of it")
                .isEqualTo(memberId);
        assertThat(blocked.getRepositoryName()).isEqualTo(MEMBER);
        assertThat(blocked.getRequestContext())
                .containsEntry("enforced", true)
                .containsEntry("blocked", true)
                .containsEntry("preExisting", false)
                .as("and it still records how the consumer got there")
                .containsEntry("viaRepository", GROUP);

        // ── Disarming reaches the group path too ─────────────────────────────
        assertThat(setEnforcement(false, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(downloadFrom(GROUP, BLOCKED).getStatusCode())
                .as("master switch off means everything is served, group or not")
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("AUDIT through a group records against the member and serves anyway")
    void auditThroughTheGroupRecordsWithoutBlocking() {
        setRepositoryMode(MEMBER, memberId, FirewallMode.AUDIT, null);
        arm();

        upload(MEMBER, AUDITED);

        assertThat(downloadFrom(GROUP, AUDITED).getStatusCode())
                .as("AUDIT never blocks — not directly, and not through a group either")
                .isEqualTo(HttpStatus.OK);

        FirewallViolationEntity recorded = awaitViolationFor(AUDITED, "audit");
        assertThat(recorded.getRepositoryId())
                .as("the observation is the member's; the group holds no component to observe")
                .isEqualTo(memberId);
        assertThat(recorded.getRepositoryName()).isEqualTo(MEMBER);
        assertThat(recorded.getRequestContext())
                .containsEntry("enforced", false)
                .containsEntry("mode", "AUDIT")
                .containsEntry("viaRepository", GROUP);
        assertThat(recorded.getAdvisoryIds()).contains(ADVISORY_ID);
    }

    @Test
    @DisplayName("the member that has the artifact decides — the group does not keep looking")
    void theBlockingMemberDecidesAndTheSearchStops() {
        armRepository(MEMBER, memberId);
        arm();

        // The same coordinates in both members. The quarantined one is first.
        upload(MEMBER, SHADOWED);
        upload(SPARE, SHADOWED);

        assertThat(downloadFrom(SPARE, SHADOWED).getStatusCode())
                .as("the spare has no firewall configuration, so directly it serves — which is "
                        + "what makes the next assertion mean something")
                .isEqualTo(HttpStatus.OK);

        assertThat(downloadFrom(GROUP, SHADOWED).getStatusCode())
                .as("falling through to the next member that happens to have the same file "
                        + "would make every group a documented bypass of its own members' policies")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("what was already stored when the switch was flipped is still served through the group")
    void grandfatheringSurvivesTheGroupPath() {
        armRepository(MEMBER, memberId);
        upload(MEMBER, GRANDFATHERED);

        arm();

        assertThat(downloadFrom(GROUP, GRANDFATHERED).getStatusCode())
                .as("arming must not break a build that depends on something already stored, "
                        + "and the group path must not be stricter than the direct one")
                .isEqualTo(HttpStatus.OK);
        assertThat(awaitEnforcementViolationFor(GRANDFATHERED).getRequestContext())
                .containsEntry("preExisting", true)
                .containsEntry("blocked", false)
                .containsEntry("viaRepository", GROUP);
    }

    @Test
    @DisplayName("a group cannot be given a firewall mode of its own")
    void theGroupItselfRefusesAMode() {
        ResponseEntity<String> refused = admin().exchange(
                url("/api/v1/admin/firewall/repositories/" + groupId),
                HttpMethod.PUT,
                json(new FirewallRepositoryModeUpdateXO(
                        FirewallMode.QUARANTINE, "QUARANTINE " + GROUP)),
                String.class);

        assertThat(refused.getStatusCode())
                .as("storing it would light up a 'Quarantine' badge over a group that blocks "
                        + "nothing — the mode belongs on the members, and saying so is the point")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getBody()).contains("group repository");
        assertThat(firewallConfigs.findById(groupId))
                .as("and a refused write really does not write")
                .isEmpty();
    }

    // ── The administration API, over HTTP, as an nx-admin ────────────────────

    private void arm() {
        assertThat(setEnforcement(true, "ENABLE ENFORCEMENT").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private void armRepository(String name, UUID id) {
        ResponseEntity<FirewallRepositoryStateXO> response =
                setRepositoryMode(name, id, FirewallMode.QUARANTINE, "QUARANTINE " + name);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().mode()).isEqualTo(FirewallMode.QUARANTINE);
    }

    private ResponseEntity<FirewallRepositoryStateXO> setRepositoryMode(
            String name, UUID id, FirewallMode mode, String confirmation) {
        ResponseEntity<FirewallRepositoryStateXO> response = admin().exchange(
                url("/api/v1/admin/firewall/repositories/" + id),
                HttpMethod.PUT,
                json(new FirewallRepositoryModeUpdateXO(mode, confirmation)),
                FirewallRepositoryStateXO.class);
        assertThat(response.getStatusCode())
                .as("setting %s to %s failed", name, mode)
                .isEqualTo(HttpStatus.OK);
        return response;
    }

    private ResponseEntity<String> setEnforcement(Boolean enabled, String confirmation) {
        return admin().exchange(
                url("/api/v1/admin/firewall/enforcement"),
                HttpMethod.PUT,
                json(new FirewallEnforcementUpdateXO(enabled, confirmation)),
                String.class);
    }

    // ── The repository API, over HTTP ────────────────────────────────────────

    private void upload(String repository, String version) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        ResponseEntity<String> response = admin().exchange(
                url("/repository/" + repository + "/" + path(version)),
                HttpMethod.PUT,
                new HttpEntity<>(ARTIFACT, headers),
                String.class);
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("upload of %s to %s failed: %s", version, repository, response.getBody())
                .isTrue();
    }

    private static String path(String version) {
        return "org/apache/logging/log4j/log4j-core/%s/log4j-core-%s.jar".formatted(version, version);
    }

    private ResponseEntity<String> downloadFrom(String repository, String version) {
        return downloadFrom(repository, version, MediaType.ALL_VALUE);
    }

    /** Anonymous, the way Maven's transport asks. */
    private ResponseEntity<String> downloadFrom(String repository, String version, String accept) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, accept);
        return rest.exchange(
                url("/repository/" + repository + "/" + path(version)),
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class);
    }

    // ── Fixture ─────────────────────────────────────────────────────────────

    private UUID givenHostedMavenRepository(String name) {
        return repositories.findByName(name)
                .map(RepositoryEntity::getId)
                .orElseGet(() -> createRepository(name, "HOSTED"));
    }

    /**
     * The group, with {@link #MEMBER} ahead of {@link #SPARE}.
     *
     * <p>The order is the test: the quarantined member is tried first, so
     * "refused" can only come from the group stopping there rather than from the
     * spare being unreachable.
     */
    private UUID givenGroup() {
        UUID id = repositories.findByName(GROUP)
                .map(RepositoryEntity::getId)
                .orElseGet(() -> createRepository(GROUP, "GROUP"));
        if (groupMembers.findByGroupRepoIdOrderBySortOrder(id).isEmpty()) {
            groupMembers.saveAndFlush(member(id, memberId, 0));
            groupMembers.saveAndFlush(member(id, spareId, 1));
        }
        return id;
    }

    private static GroupMemberEntity member(UUID groupRepoId, UUID memberRepoId, int order) {
        GroupMemberEntity entity = new GroupMemberEntity();
        entity.setGroupRepoId(groupRepoId);
        entity.setMemberRepoId(memberRepoId);
        entity.setSortOrder(order);
        return entity;
    }

    private UUID createRepository(String name, String type) {
        RepositoryEntity repository = new RepositoryEntity();
        repository.setName(name);
        repository.setFormat("maven2");
        repository.setType(type);
        repository.setOnline(true);
        repository.setBlobStoreName("default");
        repository.setCreatedAt(Instant.now());
        repository.setUpdatedAt(Instant.now());
        return repositories.saveAndFlush(repository).getId();
    }

    /** One advisory, CVSS 10.0, covering every version used here. */
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

    private FirewallViolationEntity awaitEnforcementViolationFor(String version) {
        return awaitViolationFor(version, "enforcement");
    }

    /**
     * The row written by the given phase for this version.
     *
     * <p>Polled because both paths write off the request thread: the client is
     * answered before the audit trail is, deliberately.
     */
    private FirewallViolationEntity awaitViolationFor(String version, String phase) {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            List<FirewallViolationEntity> rows = rowsFor(version, phase);
            if (!rows.isEmpty()) {
                return rows.get(0);
            }
            sleep();
        }
        throw new AssertionError("No " + phase + " row for version " + version
                + "; rows: " + violations.findAll().stream()
                        .map(row -> row.getPurl() + " " + row.getRequestContext().get("phase"))
                        .toList());
    }

    private List<FirewallViolationEntity> rowsFor(String version, String phase) {
        return violations.findAll().stream()
                .filter(row -> row.getPurl() != null && row.getPurl().endsWith("@" + version))
                .filter(row -> row.getRequestContext() != null
                        && phase.equals(row.getRequestContext().get("phase")))
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
            return Files.createTempDirectory("megarepo-firewall-group-e2e");
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
