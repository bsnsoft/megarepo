package de.bsnsoft.megarepo.app.firewall;

import de.bsnsoft.megarepo.app.MegaRepoApplication;
import de.bsnsoft.megarepo.core.firewall.FirewallExemptionScope;
import de.bsnsoft.megarepo.core.firewall.FirewallMode;
import de.bsnsoft.megarepo.core.firewall.FirewallRuleType;
import de.bsnsoft.megarepo.database.entity.AdvisoryAffectedEntity;
import de.bsnsoft.megarepo.database.entity.AdvisoryEntity;
import de.bsnsoft.megarepo.database.entity.AssetEntity;
import de.bsnsoft.megarepo.database.entity.FirewallEnforcementSettingsEntity;
import de.bsnsoft.megarepo.database.entity.FirewallExemptionEntity;
import de.bsnsoft.megarepo.database.entity.FirewallViolationEntity;
import de.bsnsoft.megarepo.database.entity.RepositoryEntity;
import de.bsnsoft.megarepo.database.repository.AdvisoryAffectedJpaRepository;
import de.bsnsoft.megarepo.database.repository.AdvisoryJpaRepository;
import de.bsnsoft.megarepo.database.repository.AssetJpaRepository;
import de.bsnsoft.megarepo.database.repository.ComponentJpaRepository;
import de.bsnsoft.megarepo.database.repository.FirewallEnforcementSettingsJpaRepository;
import de.bsnsoft.megarepo.database.repository.FirewallExemptionJpaRepository;
import de.bsnsoft.megarepo.database.repository.FirewallQuarantineJpaRepository;
import de.bsnsoft.megarepo.database.repository.FirewallRepositoryConfigJpaRepository;
import de.bsnsoft.megarepo.database.repository.FirewallViolationJpaRepository;
import de.bsnsoft.megarepo.database.repository.RepositoryJpaRepository;
import de.bsnsoft.megarepo.repository.firewall.FirewallEnforcementSettingsService;
import de.bsnsoft.megarepo.rest.dto.firewall.FirewallEnforcementUpdateXO;
import de.bsnsoft.megarepo.rest.dto.firewall.FirewallExemptionDecisionXO;
import de.bsnsoft.megarepo.rest.dto.firewall.FirewallExemptionRequestXO;
import de.bsnsoft.megarepo.rest.dto.firewall.FirewallExemptionXO;
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
 * An approved exemption means the same thing in both directions (osTicket
 * #155155).
 *
 * <h2>The complaint</h2>
 *
 * An operator approved an exemption for a component, watched it download, and
 * watched the identical component be refused when a release job published it
 * into the very same repository. Nothing about that is a corner case: it is a
 * firewall telling somebody their own decision counts in one direction only, and
 * from outside the code there is no story that explains it.
 *
 * <p>The cause was structural. The download path assembled its verdict through
 * the policy engine, which consults the exemptions; the publish path assembled a
 * second verdict by hand, and that one had no exemption step. So the fix is not
 * an exemption lookup in the upload gate — it is one assembly, run by both — and
 * the tests below are written to fail if that ever comes apart again.
 *
 * <h2>Why the assertions come in pairs</h2>
 *
 * Every scenario asserts the <em>download</em> and the <em>publish</em> of the
 * same component under the same exemption. A test that only asserted the publish
 * would pass just as happily against a hand-copied exemption step in the upload
 * gate — which is exactly the fix that was rejected, because it leaves two
 * assemblies to keep in step. Asserting both directions in one breath is what
 * turns "the publish works now" into "the two directions agree".
 *
 * <h2>Getting an enforced artifact into the repository</h2>
 *
 * A refused publish is retracted, so there is nothing left to download and no
 * way to compare the two directions. {@link #stage(String)} therefore drops the
 * repository to {@code AUDIT} for one PUT and arms it again: the artifact is
 * stored, the master switch has been on the whole time — so the asset does not
 * predate the watermark and grandfathering does not apply — and both directions
 * can then be asked about the same stored component.
 */
@SpringBootTest(
        classes = MegaRepoApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FirewallUploadExemptionEndToEndTest {

    private static final String DB_USER = "megarepo";
    private static final String DB_PASSWORD = "megarepo";

    private static final String ADMIN = "admin";
    private static final String ADMIN_PASSWORD = "admin123";

    private static final String HOSTED = "maven-exemption-e2e";

    // One version per scenario: every verdict leaves a row, and a shared version
    // would let a row from the wrong step satisfy an assertion.
    private static final String EXEMPTED = "2.14.30";
    private static final String SIBLING = "2.14.31";
    private static final String GLOBAL = "2.14.32";
    private static final String LAPSING = "2.14.33";
    private static final String UNEXEMPTED = "2.14.34";

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
        registry.add("megarepo.security.jwt.secret", () -> "firewall-exemption-end-to-end-secret");
        registry.add("megarepo.data-directory", () -> directory(WORK_DIR.resolve("data")));
        registry.add("megarepo.blob-stores.default-path", () -> directory(WORK_DIR.resolve("blobs")));
        // Longer than this class can run: a refusal has to mean the policy, never
        // a cache that lapsed.
        registry.add("megarepo.firewall.enforcement.settings-refresh-interval", () -> "10m");
        registry.add("megarepo.firewall.audit.reevaluation-interval", () -> "0s");
        // Every verdict leaves a row; the same component is judged several times
        // per test and the later verdicts are the interesting ones.
        registry.add("megarepo.firewall.audit.suppression-window", () -> "0s");
    }

    @LocalServerPort private int port;

    @Autowired private TestRestTemplate rest;
    @Autowired private FirewallEnforcementSettingsService enforcementSettings;
    @Autowired private FirewallEnforcementSettingsJpaRepository enforcementRows;
    @Autowired private FirewallRepositoryConfigJpaRepository firewallConfigs;
    @Autowired private FirewallViolationJpaRepository violations;
    @Autowired private FirewallQuarantineJpaRepository quarantineEntries;
    @Autowired private FirewallExemptionJpaRepository exemptions;
    @Autowired private RepositoryJpaRepository repositories;
    @Autowired private ComponentJpaRepository components;
    @Autowired private AssetJpaRepository assets;
    @Autowired private AdvisoryJpaRepository advisories;
    @Autowired private AdvisoryAffectedJpaRepository affected;

    private UUID hostedId;

    @BeforeEach
    void freshInstallation() {
        violations.deleteAllInBatch();
        quarantineEntries.deleteAllInBatch();
        exemptions.deleteAllInBatch();
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
        givenCriticalAdvisory();

        arm();
        armRepository();
    }

    // ── The complaint itself ────────────────────────────────────────────

    @Test
    @DisplayName("an approved exemption lets the same component be published into the same repository")
    void anApprovedExemptionLetsTheSameComponentBePublished() {
        stage(EXEMPTED);

        // ── Before: the firewall refuses it in both directions ───────────────
        assertThat(download(EXEMPTED).getStatusCode())
                .as("the policy denies this component; that is the premise, not the finding")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(publish(EXEMPTED).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // The publish above retracted the artifact. Put it back, so that "after"
        // compares the same stored component and not an empty path.
        stage(EXEMPTED);

        // ── The operator approves the exemption the 403 asked them for ───────
        UUID exemption = approveExemption(
                purl(EXEMPTED), FirewallExemptionScope.VERSION, hostedId, FirewallRuleType.CVSS_THRESHOLD);

        // ── After: both directions agree, and they agree with the operator ───
        assertThat(download(EXEMPTED).getStatusCode())
                .as("the download side has always honoured an exemption")
                .isEqualTo(HttpStatus.OK);
        assertThat(publish(EXEMPTED).getStatusCode().is2xxSuccessful())
                .as("and the publish side now does too. Refusing here while serving there tells "
                        + "the operator their own approval counts in one direction only, which is "
                        + "the report this work package answers")
                .isTrue();
        assertThat(storedAsset(EXEMPTED))
                .as("and nothing was retracted — an accepted publish leaves the artifact where a "
                        + "consumer can resolve it")
                .isPresent();

        // ── And the log says which decision let it through ───────────────────
        FirewallViolationEntity row = uploadRowFor(EXEMPTED);
        assertThat(row.getRequestContext())
                .as("a BLOCK rule that matched next to a publish that succeeded is not a readable "
                        + "audit trail on its own; the exemption that spent itself has to be on "
                        + "the row, exactly as it is for a download")
                .containsEntry("blocked", false)
                .containsEntry("decision", "EXEMPTED")
                .containsEntry("exempted", true)
                .containsEntry("exemptionId", exemption.toString());
        assertThat(row.getPurl())
                .as("and the key the firewall judged is the key the exemption named — otherwise "
                        + "this test would pass by exempting something nobody was blocking")
                .isEqualTo(purl(EXEMPTED));
    }

    // ── Scope means the same thing in both directions ───────────────────

    @Test
    @DisplayName("VERSION scope covers exactly that version, for a publish as for a download")
    void versionScopeCoversOneVersionInBothDirections() {
        stage(EXEMPTED);
        stage(SIBLING);

        approveExemption(
                purl(EXEMPTED), FirewallExemptionScope.VERSION, hostedId, FirewallRuleType.CVSS_THRESHOLD);

        assertThat(download(EXEMPTED).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(publish(EXEMPTED).getStatusCode().is2xxSuccessful()).isTrue();

        assertThat(download(SIBLING).getStatusCode())
                .as("an exemption granted because 2.14.30 is acceptable says nothing about 2.14.31")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(publish(SIBLING).getStatusCode())
                .as("and the publish side draws the line in the same place. A wider reading here "
                        + "would let a version nobody signed off be published under an approval "
                        + "for a different one")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(storedAsset(SIBLING))
                .as("the refused publish is retracted, exactly as an unexempted one is")
                .isEmpty();
    }

    @Test
    @DisplayName("COMPONENT scope covers every version, for a publish as for a download")
    void componentScopeCoversEveryVersionInBothDirections() {
        stage(EXEMPTED);
        stage(SIBLING);

        // The API normalises a versioned key to its version-less form for a
        // COMPONENT-scoped request, so the requester passes what the 403 gave them.
        approveExemption(
                purl(EXEMPTED), FirewallExemptionScope.COMPONENT, hostedId, FirewallRuleType.CVSS_THRESHOLD);

        assertThat(download(SIBLING).getStatusCode())
                .as("every version, including ones the approver never saw — the wider, deliberate "
                        + "half of the scope")
                .isEqualTo(HttpStatus.OK);
        assertThat(publish(SIBLING).getStatusCode().is2xxSuccessful())
                .as("and a publish of a sibling version is covered by exactly the same reading")
                .isTrue();
    }

    @Test
    @DisplayName("a global exemption covers the publish into any repository, not only the download")
    void aGlobalExemptionCoversThePublish() {
        stage(GLOBAL);

        approveExemption(
                purl(GLOBAL), FirewallExemptionScope.VERSION, null, FirewallRuleType.CVSS_THRESHOLD);

        assertThat(download(GLOBAL).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(publish(GLOBAL).getStatusCode().is2xxSuccessful())
                .as("a null repository_id means every repository, and 'every repository' has to "
                        + "include the one being published into")
                .isTrue();
    }

    @Test
    @DisplayName("an exemption for another rule does not cover this one, in either direction")
    void anExemptionForAnotherRuleCoversNeitherDirection() {
        stage(UNEXEMPTED);

        // Approved, live, right component, right repository — wrong rule.
        approveExemption(
                purl(UNEXEMPTED), FirewallExemptionScope.VERSION, hostedId, FirewallRuleType.MIN_AGE);

        assertThat(download(UNEXEMPTED).getStatusCode())
                .as("'exempt from the age rule' is not 'exempt'")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(publish(UNEXEMPTED).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("an expired exemption blocks the publish again, exactly as it blocks the download")
    void anExpiredExemptionBlocksThePublishAgain() {
        stage(LAPSING);

        UUID exemption = approveExemption(
                purl(LAPSING), FirewallExemptionScope.VERSION, hostedId, FirewallRuleType.CVSS_THRESHOLD,
                Instant.now().plus(Duration.ofDays(7)));

        assertThat(download(LAPSING).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(publish(LAPSING).getStatusCode().is2xxSuccessful()).isTrue();

        // Time passes. Backdated in the row rather than slept through: the expiry
        // is read on the request path from the column, so moving the column is
        // exactly the event being tested and nothing about it is timing-sensitive.
        // The state stays APPROVED on purpose — the daily sweep has not run, and an
        // exemption that lapsed at noon has to stop applying at noon.
        FirewallExemptionEntity lapsed = exemptions.findById(exemption).orElseThrow();
        lapsed.setExpiresAt(Instant.now().minus(Duration.ofMinutes(1)));
        exemptions.saveAndFlush(lapsed);

        assertThat(download(LAPSING).getStatusCode())
                .as("an expired exemption blocks again — the behaviour the V8 whitelist could not "
                        + "express, and the reason exemptions have an expiry at all")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(publish(LAPSING).getStatusCode())
                .as("and it stops applying to a publish at the same instant. An exemption that "
                        + "outlived its expiry on the write path would be a permanent hole with a "
                        + "date on it")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── Staging and the two request paths ───────────────────────────────

    /**
     * Puts the artifact into the armed repository without the firewall refusing
     * it, so that both directions can be asked about the same stored component.
     *
     * <p>{@code AUDIT} for exactly one PUT. The master switch stays on throughout,
     * so the asset's {@code created_at} is after the watermark and the
     * grandfathering rule — which would let every assertion below pass for the
     * wrong reason — does not apply.
     */
    private void stage(String version) {
        setRepositoryMode(FirewallMode.AUDIT, null);
        assertThat(publish(version).getStatusCode().is2xxSuccessful())
                .as("staging %s should not be refused while the repository is only observing", version)
                .isTrue();
        armRepository();
        assertThat(storedAsset(version))
                .as("staging %s left nothing in the repository", version)
                .isPresent();
    }

    private ResponseEntity<String> publish(String version) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.set(HttpHeaders.ACCEPT, MediaType.ALL_VALUE);
        return admin().exchange(
                url("/repository/" + HOSTED + "/" + path(version)),
                HttpMethod.PUT,
                new HttpEntity<>(ARTIFACT, headers),
                String.class);
    }

    private ResponseEntity<String> download(String version) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, MediaType.ALL_VALUE);
        return rest.exchange(
                url("/repository/" + HOSTED + "/" + path(version)),
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class);
    }

    private static String path(String version) {
        return "org/apache/logging/log4j/log4j-core/%s/log4j-core-%s.jar".formatted(version, version);
    }

    private static String purl(String version) {
        return "pkg:maven/org.apache.logging.log4j/log4j-core@" + version;
    }

    // ── The exemption workflow, over HTTP, as an nx-admin ───────────────

    private UUID approveExemption(
            String componentKey, FirewallExemptionScope scope, UUID repositoryId, FirewallRuleType rule) {
        return approveExemption(componentKey, scope, repositoryId, rule, null);
    }

    /** Requests one and approves it, the way the block page's link and an approver would. */
    private UUID approveExemption(
            String componentKey,
            FirewallExemptionScope scope,
            UUID repositoryId,
            FirewallRuleType rule,
            Instant expiresAt) {

        ResponseEntity<FirewallExemptionXO> requested = admin().exchange(
                url("/api/v1/firewall/exemptions"),
                HttpMethod.POST,
                json(new FirewallExemptionRequestXO(
                        componentKey, scope, repositoryId, rule, List.of(), null,
                        "the fixed release is not out yet and the build has to ship")),
                FirewallExemptionXO.class);
        assertThat(requested.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(requested.getBody()).isNotNull();
        UUID id = requested.getBody().id();

        ResponseEntity<FirewallExemptionXO> approved = admin().exchange(
                url("/api/v1/firewall/exemptions/" + id + "/approve"),
                HttpMethod.POST,
                json(new FirewallExemptionDecisionXO(expiresAt, "signed off for one sprint")),
                FirewallExemptionXO.class);
        assertThat(approved.getStatusCode())
                .as("approving %s failed: %s", id, approved.getBody())
                .isEqualTo(HttpStatus.OK);
        return id;
    }

    // ── The administration API ──────────────────────────────────────────

    private void arm() {
        ResponseEntity<String> armed = admin().exchange(
                url("/api/v1/admin/firewall/enforcement"),
                HttpMethod.PUT,
                json(new FirewallEnforcementUpdateXO(true, "ENABLE ENFORCEMENT")),
                String.class);
        assertThat(armed.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private void armRepository() {
        setRepositoryMode(FirewallMode.QUARANTINE, "QUARANTINE " + HOSTED);
    }

    private void setRepositoryMode(FirewallMode mode, String confirmation) {
        ResponseEntity<FirewallRepositoryStateXO> response = admin().exchange(
                url("/api/v1/admin/firewall/repositories/" + hostedId),
                HttpMethod.PUT,
                json(new FirewallRepositoryModeUpdateXO(mode, confirmation)),
                FirewallRepositoryStateXO.class);
        assertThat(response.getStatusCode())
                .as("setting %s to %s failed", HOSTED, mode)
                .isEqualTo(HttpStatus.OK);
    }

    // ── Reading what survived ───────────────────────────────────────────

    private Optional<AssetEntity> storedAsset(String version) {
        return assets.findByRepositoryIdAndPath(hostedId, path(version));
    }

    /**
     * The most recent enforcement row this version's <em>publish</em> wrote.
     *
     * <p>Deliberately not polled. The download path records off the request
     * thread; the publish path records inline while the client is still being
     * answered, and a poll here would hide a regression that moved it off-thread.
     */
    private FirewallViolationEntity uploadRowFor(String version) {
        List<FirewallViolationEntity> rows = violations.findAll().stream()
                .filter(row -> purl(version).equals(row.getPurl()))
                .filter(row -> row.getRequestContext() != null
                        && "enforcement".equals(row.getRequestContext().get("phase"))
                        && "PUT".equals(row.getRequestContext().get("method")))
                .sorted((a, b) -> b.getOccurredAt().compareTo(a.getOccurredAt()))
                .toList();
        assertThat(rows)
                .as("the publish of %s recorded nothing; rows: %s", version,
                        violations.findAll().stream()
                                .map(row -> row.getPurl() + " " + row.getRequestContext())
                                .toList())
                .isNotEmpty();
        return rows.get(0);
    }

    // ── Fixture ─────────────────────────────────────────────────────────

    private UUID givenHostedMavenRepository() {
        return repositories.findByName(HOSTED)
                .map(RepositoryEntity::getId)
                .orElseGet(() -> {
                    RepositoryEntity repository = new RepositoryEntity();
                    repository.setName(HOSTED);
                    repository.setFormat("maven2");
                    repository.setType("HOSTED");
                    repository.setOnline(true);
                    repository.setBlobStoreName("default");
                    repository.setAttributes(new java.util.HashMap<>(Map.of()));
                    repository.setCreatedAt(Instant.now());
                    repository.setUpdatedAt(Instant.now());
                    return repositories.saveAndFlush(repository).getId();
                });
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

    // ── Plumbing ────────────────────────────────────────────────────────

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
            return Files.createTempDirectory("megarepo-firewall-exemption-e2e");
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
