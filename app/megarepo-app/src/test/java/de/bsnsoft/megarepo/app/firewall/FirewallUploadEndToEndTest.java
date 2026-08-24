package de.bsnsoft.megarepo.app.firewall;

import de.bsnsoft.megarepo.app.MegaRepoApplication;
import de.bsnsoft.megarepo.core.firewall.FirewallMode;
import de.bsnsoft.megarepo.database.entity.AdvisoryAffectedEntity;
import de.bsnsoft.megarepo.database.entity.AdvisoryEntity;
import de.bsnsoft.megarepo.database.entity.AssetEntity;
import de.bsnsoft.megarepo.database.entity.ComponentEntity;
import de.bsnsoft.megarepo.database.entity.FirewallEnforcementSettingsEntity;
import de.bsnsoft.megarepo.database.entity.FirewallViolationEntity;
import de.bsnsoft.megarepo.database.entity.GroupMemberEntity;
import de.bsnsoft.megarepo.database.entity.RepositoryEntity;
import de.bsnsoft.megarepo.database.repository.AdvisoryAffectedJpaRepository;
import de.bsnsoft.megarepo.database.repository.AdvisoryJpaRepository;
import de.bsnsoft.megarepo.database.repository.AssetJpaRepository;
import de.bsnsoft.megarepo.database.repository.ComponentJpaRepository;
import de.bsnsoft.megarepo.database.repository.FirewallEnforcementSettingsJpaRepository;
import de.bsnsoft.megarepo.database.repository.FirewallQuarantineJpaRepository;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The publish side of the firewall: what a {@code mvn deploy} into an armed
 * repository does (osTicket #155155).
 *
 * <h2>Why the upload path needed its own end-to-end test</h2>
 *
 * Phase 1 never looked at a publish. A repository whose downloads are gated while
 * its uploads are not is a repository with an unlocked back door: what a
 * developer publishes into a hosted repository is what every consumer of it then
 * pulls, and the download gate has nothing to say about a component it has
 * already handed out. So the PUT path now runs the same policy — and that is a
 * change that can only be proved where a real HTTP PUT meets a real format
 * handler, a real blob store and a real database. Neither the evaluator's unit
 * tests (which are handed a component) nor the router's (which are handed a
 * verdict) can show that the artifact really is gone afterwards.
 *
 * <h2>The upgrade-safety promise comes first</h2>
 *
 * {@link #everyPublishIsAcceptedWhileNothingIsEnforcing()} is deliberately the
 * first test in the file. The overwhelming majority of installations will never
 * arm this feature, and for them the correct observable effect of the whole work
 * package is <em>none</em>: the same PUT, the same 201, the same artifact. A
 * release job that starts failing because somebody upgraded MegaRepo is a worse
 * outcome than any vulnerability this feature prevents, and it is the failure
 * mode a reviewer should see asserted before anything else.
 *
 * <h2>The retraction is the part that breaks</h2>
 *
 * A refusal cannot happen before the write: the component only exists once the
 * format handler has extracted its coordinates, and the layout grammar for that
 * lives in the format module. So a refused publish has to be <em>undone</em> —
 * and "the client got a 403" is not evidence that it was. Every refusal below
 * therefore asserts three separate things: the status, that a subsequent GET is a
 * 404, and that no {@code assets} row survives for the path. The last one is the
 * one that would catch a retraction that deleted the blob and left the row, or
 * deleted from the wrong repository.
 *
 * <h2>What is real here</h2>
 *
 * Everything, on the same terms as {@code FirewallSwitchEndToEndTest}: the whole
 * application on a real port, a real PostgreSQL with the real migrations, real
 * HTTP to {@code /api/v1/admin/firewall/**} as an {@code nx-admin} and real
 * {@code PUT}/{@code GET} against {@code /repository/**}.
 */
@SpringBootTest(
        classes = MegaRepoApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FirewallUploadEndToEndTest {

    private static final String DB_USER = "megarepo";
    private static final String DB_PASSWORD = "megarepo";

    private static final String ADMIN = "admin";
    private static final String ADMIN_PASSWORD = "admin123";

    /** The hosted repository releases are published into. */
    private static final String HOSTED = "maven-upload-e2e";

    /** A group in front of it, with {@link #HOSTED} as its writable member. */
    private static final String GROUP = "maven-upload-e2e-group";

    /**
     * The sentence an administrator configures. Asserted, because "appended, so
     * no configuration can produce a 403 that fails to say what was blocked" is
     * only worth something if the configured half actually arrives.
     */
    private static final String CONTACT = "Ask #platform-security if you need this dependency.";

    // One version per assertion. The publish gate and the download gate both
    // write to firewall_violation, and a shared version would let a row from the
    // wrong step satisfy an assertion.
    private static final String WHILE_SWITCH_OFF = "2.14.20";
    private static final String WHILE_MERELY_AUDITING = "2.14.21";
    private static final String REFUSED = "2.14.22";
    private static final String REFUSED_VIA_GROUP = "2.14.23";
    private static final String REPUBLISHED = "2.14.24";

    /** Outside the advisory's range: nothing names it, so nothing refuses it. */
    private static final String CLEAN = "3.1.0";
    private static final String CLEAN_VIA_GROUP = "3.1.1";

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
        registry.add("megarepo.security.jwt.secret", () -> "firewall-upload-end-to-end-test-secret");
        registry.add("megarepo.data-directory", () -> directory(WORK_DIR.resolve("data")));
        registry.add("megarepo.blob-stores.default-path", () -> directory(WORK_DIR.resolve("blobs")));
        // Longer than this class can run: an assertion about a publish being
        // refused has to mean the API armed it, never that a cache lapsed.
        registry.add("megarepo.firewall.enforcement.settings-refresh-interval", () -> "10m");
        registry.add("megarepo.firewall.audit.reevaluation-interval", () -> "0s");
        // Every verdict leaves a row. Several paths below are published and then
        // downloaded, and the second verdict is the interesting one.
        registry.add("megarepo.firewall.audit.suppression-window", () -> "0s");
        registry.add("megarepo.firewall.block.contact-message", () -> CONTACT);
    }

    @LocalServerPort private int port;

    @Autowired private TestRestTemplate rest;
    @Autowired private FirewallEnforcementSettingsService enforcementSettings;
    @Autowired private FirewallEnforcementSettingsJpaRepository enforcementRows;
    @Autowired private FirewallRepositoryConfigJpaRepository firewallConfigs;
    @Autowired private FirewallViolationJpaRepository violations;
    @Autowired private FirewallQuarantineJpaRepository quarantineEntries;
    @Autowired private RepositoryJpaRepository repositories;
    @Autowired private GroupMemberJpaRepository groupMembers;
    @Autowired private ComponentJpaRepository components;
    @Autowired private AssetJpaRepository assets;
    @Autowired private AdvisoryJpaRepository advisories;
    @Autowired private AdvisoryAffectedJpaRepository affected;

    private UUID hostedId;
    private UUID groupId;

    @BeforeEach
    void freshInstallation() {
        violations.deleteAllInBatch();
        quarantineEntries.deleteAllInBatch();
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

        hostedId = givenHostedMavenRepository();
        groupId = givenGroupWritingInto(hostedId);
        givenCriticalAdvisory();
    }

    @Test
    @DisplayName("nothing about a publish changes until both switches are on")
    void everyPublishIsAcceptedWhileNothingIsEnforcing() {
        // ── The instance most installations will be running ──────────────────
        armRepository();
        assertThat(publish(HOSTED, WHILE_SWITCH_OFF).getStatusCode().is2xxSuccessful())
                .as("QUARANTINE on the repository means nothing at all while the master switch "
                        + "is off — that is what makes an upgrade into this build invisible")
                .isTrue();
        assertThat(download(HOSTED, WHILE_SWITCH_OFF).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(storedAsset(hostedId, WHILE_SWITCH_OFF)).isPresent();

        // ── Armed, but this repository only wants to be observed ─────────────
        arm();
        setRepositoryMode(FirewallMode.AUDIT, null);

        assertThat(publish(HOSTED, WHILE_MERELY_AUDITING).getStatusCode().is2xxSuccessful())
                .as("AUDIT observes and never withholds, and a publish is not the exception: "
                        + "an operator evaluating the firewall must be able to leave a release "
                        + "job alone while doing it")
                .isTrue();
        assertThat(download(HOSTED, WHILE_MERELY_AUDITING).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(refusalsRecorded())
                .as("and no verdict anywhere says a publish was denied")
                .isEmpty();
    }

    @Test
    @DisplayName("an armed repository refuses the publish and keeps nothing behind")
    void aDeniedPublishIsRefusedAndRetracted() {
        arm();
        armRepository();

        ResponseEntity<String> refused = publish(HOSTED, REFUSED);

        // ── The answer the release job gets ──────────────────────────────────
        assertThat(refused.getStatusCode())
                .as("the same verdict a download of this component would get, at the moment it "
                        + "still costs one developer a build rather than every consumer a pull")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(refused.getHeaders().getFirst("X-MegaRepo-Firewall")).isEqualTo("blocked");
        assertThat(refused.getHeaders().getFirst("X-MegaRepo-Firewall-Rule"))
                .contains("CVSS_THRESHOLD");
        assertThat(refused.getHeaders().getFirst("X-MegaRepo-Firewall-Advisories"))
                .contains(ADVISORY_ID);
        assertThat(refused.getHeaders().getFirst("X-MegaRepo-Firewall-Exemption-Request"))
                .as("the next step after a refused publish should be a request, not a message "
                        + "to whoever owns the repository manager")
                .isNotBlank();
        assertThat(refused.getBody())
                .as("this is read in a CI log by somebody who cannot see MegaRepo, so it carries "
                        + "the same explanation a blocked download does: what, which rule, which "
                        + "advisory, which policy, and how to ask for an exemption")
                .contains("MegaRepo repository firewall")
                .contains(HOSTED)
                .contains("log4j-core@" + REFUSED)
                .contains("CVSS_THRESHOLD")
                .contains(ADVISORY_ID)
                .contains("Policy     : ")
                .contains("To ask for an exemption:")
                .contains(CONTACT);
        assertThat(refused.getBody())
                .as("and it says publish, not download: the developer is holding a failed "
                        + "release job, and a body telling them 'the artifact itself is "
                        + "untouched' would be false — it was written and retracted again")
                .contains("MegaRepo repository firewall: this publish was blocked.")
                .contains("Nothing was published")
                .doesNotContain("this download was blocked")
                .doesNotContain("The artifact itself is untouched");

        // ── And nothing was published ────────────────────────────────────────
        assertThat(download(HOSTED, REFUSED).getStatusCode())
                .as("a 403 on the publish and a servable artifact afterwards would be the worst "
                        + "of both: the release job failed and the component went out anyway")
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(storedAsset(hostedId, REFUSED))
                .as("the retraction goes through the format handler's own delete, so the asset "
                        + "row and the blob go the way a real DELETE would take them — asserted "
                        + "directly, because a status code cannot tell the difference between "
                        + "'retracted' and 'retracted from the wrong repository'")
                .isEmpty();

        // ── The refusal is in the log, written while the client waited ───────
        FirewallViolationEntity recorded = enforcementRowFor(REFUSED);
        assertThat(recorded.getRequestContext())
                .as("not polled: unlike a download, the upload verdict is recorded inline — the "
                        + "client is still being answered and already paying for a blob write")
                .containsEntry("phase", "enforcement")
                .containsEntry("blocked", true)
                .containsEntry("preExisting", false)
                .containsEntry("method", "PUT");
        assertThat(recorded.getRepositoryId()).isEqualTo(hostedId);

        // ── What the retraction does *not* clean up ──────────────────────────
        assertThat(componentRow(hostedId, REFUSED))
                .as("the component row the PUT created outlives the asset: handleHostedDelete "
                        + "removes the asset and its blob and nothing else. Harmless for the "
                        + "firewall — with no asset there is nothing to serve and nothing to "
                        + "resolve — but it is a real leftover, asserted here so that a future "
                        + "change to the retraction is a deliberate one")
                .isPresent();
    }

    @Test
    @DisplayName("a clean publish into the very same armed repository still succeeds")
    void aCleanPublishIntoAnArmedRepositoryIsUntouched() {
        arm();
        armRepository();

        assertThat(publish(HOSTED, CLEAN).getStatusCode().is2xxSuccessful())
                .as("arming a repository denies what the policy denies, not everything — a "
                        + "firewall that fails every release is one an operator switches off")
                .isTrue();

        ResponseEntity<String> served = download(HOSTED, CLEAN);
        assertThat(served.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(served.getBody()).isEqualTo(new String(ARTIFACT, StandardCharsets.UTF_8));
        assertThat(storedAsset(hostedId, CLEAN)).isPresent();
    }

    @Test
    @DisplayName("through a group the writable member's policy decides, and a refusal retracts from the member")
    void aPublishThroughAGroupIsJudgedAndRetractedInTheMember() {
        arm();
        armRepository();

        ResponseEntity<String> refused = publish(GROUP, REFUSED_VIA_GROUP);

        assertThat(refused.getStatusCode())
                .as("a group stores nothing; the publish lands in its writable member, and it "
                        + "is that member's mode and policy that govern it")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(refused.getBody())
                .as("the developer deployed to the group and has never heard of the member, so "
                        + "the refusal has to name both and say which of them decided")
                .contains("Requested  : " + GROUP + " (group)")
                .contains("Resolved by: " + HOSTED)
                .contains("log4j-core@" + REFUSED_VIA_GROUP);

        assertThat(storedAsset(hostedId, REFUSED_VIA_GROUP))
                .as("retracted from the member. Retracting from the group would delete nothing "
                        + "at all, and the artifact would stay servable through the same URL it "
                        + "was refused on")
                .isEmpty();
        assertThat(storedAsset(groupId, REFUSED_VIA_GROUP))
                .as("and the group never held a row to begin with")
                .isEmpty();
        assertThat(download(GROUP, REFUSED_VIA_GROUP).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(download(HOSTED, REFUSED_VIA_GROUP).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(enforcementRowFor(REFUSED_VIA_GROUP).getRequestContext())
                .containsEntry("blocked", true)
                .as("and the row records the route the publisher took as well as the repository "
                        + "an operator would have to go and change")
                .containsEntry("viaRepository", GROUP);
        assertThat(enforcementRowFor(REFUSED_VIA_GROUP).getRepositoryId()).isEqualTo(hostedId);

        // The other half of the same rule: the member decides, and when it has
        // nothing against the component the publish goes through the group as it
        // always did.
        assertThat(publish(GROUP, CLEAN_VIA_GROUP).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(storedAsset(hostedId, CLEAN_VIA_GROUP))
                .as("stored in the member, which is where a group's writes have always gone")
                .isPresent();
        assertThat(download(GROUP, CLEAN_VIA_GROUP).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("re-publishing a path that predates the switch is still accepted")
    void aPathThatPredatesTheSwitchIsStillAccepted() {
        armRepository();
        assertThat(publish(HOSTED, REPUBLISHED).getStatusCode().is2xxSuccessful())
                .as("published while the instance was still disarmed")
                .isTrue();

        arm();

        assertThat(publish(HOSTED, REPUBLISHED).getStatusCode().is2xxSuccessful())
                .as("the grandfathering rule does not stop at downloads. Arming the firewall "
                        + "may not turn a release job that worked yesterday into a failing one, "
                        + "and re-deploying an existing path is exactly that job")
                .isTrue();
        assertThat(download(HOSTED, REPUBLISHED).getStatusCode())
                .as("and what was accepted stays servable, for the same reason")
                .isEqualTo(HttpStatus.OK);
        assertThat(storedAsset(hostedId, REPUBLISHED)).isPresent();
    }

    // ── The administration API, over HTTP, as an nx-admin ────────────────────

    private void arm() {
        ResponseEntity<String> armed = admin().exchange(
                url("/api/v1/admin/firewall/enforcement"),
                HttpMethod.PUT,
                json(new FirewallEnforcementUpdateXO(true, "ENABLE ENFORCEMENT")),
                String.class);
        assertThat(armed.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private void armRepository() {
        ResponseEntity<FirewallRepositoryStateXO> response =
                setRepositoryMode(FirewallMode.QUARANTINE, "QUARANTINE " + HOSTED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().mode()).isEqualTo(FirewallMode.QUARANTINE);
    }

    private ResponseEntity<FirewallRepositoryStateXO> setRepositoryMode(
            FirewallMode mode, String confirmation) {
        ResponseEntity<FirewallRepositoryStateXO> response = admin().exchange(
                url("/api/v1/admin/firewall/repositories/" + hostedId),
                HttpMethod.PUT,
                json(new FirewallRepositoryModeUpdateXO(mode, confirmation)),
                FirewallRepositoryStateXO.class);
        assertThat(response.getStatusCode())
                .as("setting %s to %s failed", HOSTED, mode)
                .isEqualTo(HttpStatus.OK);
        return response;
    }

    // ── The repository API, over HTTP ────────────────────────────────────────

    /**
     * A {@code mvn deploy}, as one authenticated PUT.
     *
     * <p>{@code Accept} is pinned to {@code *&#47;*} rather than left to
     * {@link TestRestTemplate}, which would offer {@code application/json} on the
     * strength of its converters and get back the JSON shape of a refusal. Maven's
     * transport offers no such thing, so the body a failing {@code mvn deploy}
     * actually prints is the plain-text one — which is the body worth asserting.
     */
    private ResponseEntity<String> publish(String repository, String version) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.set(HttpHeaders.ACCEPT, MediaType.ALL_VALUE);
        return admin().exchange(
                url("/repository/" + repository + "/" + path(version)),
                HttpMethod.PUT,
                new HttpEntity<>(ARTIFACT, headers),
                String.class);
    }

    /** Anonymous, the way Maven's transport asks. */
    private ResponseEntity<String> download(String repository, String version) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, MediaType.ALL_VALUE);
        return rest.exchange(
                url("/repository/" + repository + "/" + path(version)),
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class);
    }

    private static String path(String version) {
        return "org/apache/logging/log4j/log4j-core/%s/log4j-core-%s.jar".formatted(version, version);
    }

    // ── Reading what actually survived ──────────────────────────────────────

    private Optional<AssetEntity> storedAsset(UUID repositoryId, String version) {
        return assets.findByRepositoryIdAndPath(repositoryId, path(version));
    }

    private Optional<ComponentEntity> componentRow(UUID repositoryId, String version) {
        return components.findAll().stream()
                .filter(row -> repositoryId.equals(row.getRepositoryId()))
                .filter(row -> version.equals(row.getVersion()))
                .findFirst();
    }

    /**
     * The enforcement row for this version.
     *
     * <p>Deliberately not polled. The download path records off the request
     * thread and has to be waited for; the upload path records inline, and a poll
     * here would hide a regression that moved it off-thread — which would mean a
     * refused publish whose reason reaches the log only sometimes.
     */
    private FirewallViolationEntity enforcementRowFor(String version) {
        List<FirewallViolationEntity> rows = violations.findAll().stream()
                .filter(row -> row.getPurl() != null && row.getPurl().endsWith("@" + version))
                .filter(row -> row.getRequestContext() != null
                        && "enforcement".equals(row.getRequestContext().get("phase")))
                .toList();
        assertThat(rows)
                .as("the enforcement path recorded nothing for version %s; rows: %s",
                        version,
                        violations.findAll().stream()
                                .map(row -> row.getPurl() + " " + row.getRequestContext())
                                .toList())
                .isNotEmpty();
        return rows.get(0);
    }

    /** Every row claiming something was denied, whichever path wrote it. */
    private List<FirewallViolationEntity> refusalsRecorded() {
        return violations.findAll().stream()
                .filter(row -> row.getRequestContext() != null
                        && Boolean.TRUE.equals(row.getRequestContext().get("blocked")))
                .toList();
    }

    // ── Fixture ─────────────────────────────────────────────────────────────

    private UUID givenHostedMavenRepository() {
        return repositories.findByName(HOSTED)
                .map(RepositoryEntity::getId)
                .orElseGet(() -> createRepository(HOSTED, "HOSTED", Map.of()));
    }

    /**
     * The group in front of it.
     *
     * <p>{@code writableMember} is what makes a PUT to the group resolvable at
     * all — a group with no writable member answers 405 — and naming it here is
     * what lets the assertions above be about the firewall rather than about
     * routing.
     */
    private UUID givenGroupWritingInto(UUID memberId) {
        UUID id = repositories.findByName(GROUP)
                .map(RepositoryEntity::getId)
                .orElseGet(() -> createRepository(
                        GROUP, "GROUP", Map.of("group", Map.of("writableMember", HOSTED))));
        if (groupMembers.findByGroupRepoIdOrderBySortOrder(id).isEmpty()) {
            GroupMemberEntity member = new GroupMemberEntity();
            member.setGroupRepoId(id);
            member.setMemberRepoId(memberId);
            member.setSortOrder(0);
            groupMembers.saveAndFlush(member);
        }
        return id;
    }

    private UUID createRepository(String name, String type, Map<String, Object> attributes) {
        RepositoryEntity repository = new RepositoryEntity();
        repository.setName(name);
        repository.setFormat("maven2");
        repository.setType(type);
        repository.setOnline(true);
        repository.setBlobStoreName("default");
        repository.setAttributes(new java.util.HashMap<>(attributes));
        repository.setCreatedAt(Instant.now());
        repository.setUpdatedAt(Instant.now());
        return repositories.saveAndFlush(repository).getId();
    }

    /** One advisory, CVSS 10.0, covering every {@code 2.14.x} version used here. */
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

    private static Path createWorkDir() {
        try {
            return Files.createTempDirectory("megarepo-firewall-upload-e2e");
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

    private static void sleep() {
        try {
            Thread.sleep(25);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for PostgreSQL", e);
        }
    }
}
