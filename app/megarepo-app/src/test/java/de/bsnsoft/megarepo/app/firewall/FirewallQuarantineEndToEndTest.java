package de.bsnsoft.megarepo.app.firewall;

import de.bsnsoft.megarepo.app.MegaRepoApplication;
import de.bsnsoft.megarepo.core.firewall.FirewallAction;
import de.bsnsoft.megarepo.core.firewall.FirewallExemptionScope;
import de.bsnsoft.megarepo.core.firewall.FirewallExemptionState;
import de.bsnsoft.megarepo.core.firewall.FirewallFactsState;
import de.bsnsoft.megarepo.core.firewall.FirewallFailMode;
import de.bsnsoft.megarepo.core.firewall.FirewallMode;
import de.bsnsoft.megarepo.core.firewall.FirewallQuarantineReason;
import de.bsnsoft.megarepo.core.firewall.FirewallQuarantineState;
import de.bsnsoft.megarepo.core.firewall.FirewallRuleType;
import de.bsnsoft.megarepo.database.entity.AdvisoryAffectedEntity;
import de.bsnsoft.megarepo.database.entity.AdvisoryEntity;
import de.bsnsoft.megarepo.database.entity.FirewallComponentFactsEntity;
import de.bsnsoft.megarepo.database.entity.FirewallEnforcementSettingsEntity;
import de.bsnsoft.megarepo.database.entity.FirewallExemptionEntity;
import de.bsnsoft.megarepo.database.entity.FirewallPolicyEntity;
import de.bsnsoft.megarepo.database.entity.FirewallQuarantineEntity;
import de.bsnsoft.megarepo.database.entity.FirewallViolationEntity;
import de.bsnsoft.megarepo.database.entity.RepositoryEntity;
import de.bsnsoft.megarepo.database.repository.AdvisoryAffectedJpaRepository;
import de.bsnsoft.megarepo.database.repository.AdvisoryJpaRepository;
import de.bsnsoft.megarepo.database.repository.AssetJpaRepository;
import de.bsnsoft.megarepo.database.repository.ComponentJpaRepository;
import de.bsnsoft.megarepo.database.repository.FirewallComponentFactsJpaRepository;
import de.bsnsoft.megarepo.database.repository.FirewallEnforcementSettingsJpaRepository;
import de.bsnsoft.megarepo.database.repository.FirewallExemptionJpaRepository;
import de.bsnsoft.megarepo.database.repository.FirewallPolicyJpaRepository;
import de.bsnsoft.megarepo.database.repository.FirewallPolicyRuleJpaRepository;
import de.bsnsoft.megarepo.database.repository.FirewallQuarantineJpaRepository;
import de.bsnsoft.megarepo.database.repository.FirewallRepositoryConfigJpaRepository;
import de.bsnsoft.megarepo.database.repository.FirewallViolationJpaRepository;
import de.bsnsoft.megarepo.database.repository.RepositoryJpaRepository;
import de.bsnsoft.megarepo.repository.firewall.FirewallEnforcementSettingsService;
import de.bsnsoft.megarepo.rest.dto.firewall.FirewallEnforcementUpdateXO;
import de.bsnsoft.megarepo.rest.dto.firewall.FirewallExemptionDecisionXO;
import de.bsnsoft.megarepo.rest.dto.firewall.FirewallExemptionRequestXO;
import de.bsnsoft.megarepo.rest.dto.firewall.FirewallExemptionXO;
import de.bsnsoft.megarepo.rest.dto.firewall.FirewallPolicyRuleXO;
import de.bsnsoft.megarepo.rest.dto.firewall.FirewallPolicyUpsertXO;
import de.bsnsoft.megarepo.rest.dto.firewall.FirewallPolicyXO;
import de.bsnsoft.megarepo.rest.dto.firewall.FirewallQuarantineDecisionXO;
import de.bsnsoft.megarepo.rest.dto.firewall.FirewallQuarantineEntryXO;
import de.bsnsoft.megarepo.rest.dto.firewall.FirewallRepositoryModeUpdateXO;
import de.bsnsoft.megarepo.rest.dto.firewall.FirewallRepositoryPolicyUpdateXO;
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
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What an armed firewall does with a component it is <em>not</em> sure about:
 * holds it, says so, lets it out again — and what it never does, which is put a
 * critical advisory into a queue with a release button next to it
 * (osTicket #155155, wave B1).
 *
 * <h2>Why this is a second end-to-end class</h2>
 *
 * {@code FirewallSwitchEndToEndTest} proves the seam between the administration
 * API and a download for the one verdict Phase 1 had: refused, full stop. Phase 2
 * added three more, and all three are only true if several parts agree at once —
 * a rule that asks to hold rather than to refuse, a queue entry written on the
 * request path, a short-circuit that reads it back on the next request, and an
 * exemption store that can overrule the rule before either happens. Each half has
 * its own unit and database tests; none of them can show that a client asking for
 * the artifact gets the answer the design promises.
 *
 * <p>So, like its sibling: the whole {@link MegaRepoApplication} on a real port, a
 * real PostgreSQL with the real migrations, real artifacts published over HTTP,
 * and every operator action — arming, the policy, the release, the exemption —
 * taken through the API an {@code nx-admin} would use. Nothing here calls a
 * firewall service to make something happen. Two fixtures are written straight to
 * the database, and both are stated where they are used: the component facts,
 * which in production arrive from a background resolver this test deliberately
 * switches off, and the backdating of an exemption's expiry, which the API
 * refuses to do on purpose (§{@link #anExpiredExemptionBlocksAgain()}).
 *
 * <h2>The order the fixture publishes in, and why it cannot be simplified</h2>
 *
 * Every method here follows the same five steps:
 *
 * <ol>
 *   <li>publish {@link #PRE_EXISTING} while nothing is armed — the artifact that
 *       was "already in the repository";</li>
 *   <li>arm the master switch, which stamps {@code enforcing_since}: the
 *       watermark that separates the two;</li>
 *   <li>publish {@link #FRESH} — accepted, because the repository is not in
 *       QUARANTINE yet, and stored after the watermark, so it is not
 *       grandfathered;</li>
 *   <li>put the repository into QUARANTINE;</li>
 *   <li>download.</li>
 * </ol>
 *
 * <p>Step 3 has to happen before step 4 and that is not an accident of ordering:
 * as of B1 an <em>upload</em> into an enforcing hosted repository is judged too,
 * so publishing a denied component while the repository is armed is itself
 * refused and the fixture would never get the artifact in. Anybody tempted to
 * "simplify" this into publish-then-arm-everything will get a test that fails for
 * a reason that has nothing to do with what it is asserting.
 *
 * <h2>Two versions of one artifact per test</h2>
 *
 * {@link #PRE_EXISTING} and {@link #FRESH} are two versions of the same
 * component, so the grandfathering assertion is never vacuous: the older one
 * trips exactly the same rule as the newer one — same advisory range, same freshly
 * published date, same missing facts — and is served anyway. Each method uses its
 * own artifact id, because the violation recorder writes off the request thread
 * and a row from the previous method can still land during this one.
 */
@SpringBootTest(
        classes = MegaRepoApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FirewallQuarantineEndToEndTest {

    private static final String DB_USER = "megarepo";
    private static final String DB_PASSWORD = "megarepo";

    private static final String ADMIN = "admin";
    private static final String ADMIN_PASSWORD = "admin123";

    private static final String REPOSITORY = "maven-quarantine-e2e";

    private static final String GROUP_ID = "com.acme";

    /** Published before the master switch was armed: audited, never blocked. */
    private static final String PRE_EXISTING = "0.9.0";

    /** Published after it: the version every assertion here is about. */
    private static final String FRESH = "1.0.0";

    private static final String ADVISORY_ID = "GHSA-quarantine-e2e-crit";

    private static final byte[] ARTIFACT = "PK pretend jar".getBytes(StandardCharsets.UTF_8);

    /**
     * A minimum age nothing in this test can grow into, so "held" never decays
     * into "released by itself" while the method is still running.
     */
    private static final String MIN_AGE = "P7D";

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
        registry.add("megarepo.security.jwt.secret", () -> "firewall-quarantine-e2e-test-secret-key");
        registry.add("megarepo.data-directory", () -> directory(WORK_DIR.resolve("data")));
        registry.add("megarepo.blob-stores.default-path", () -> directory(WORK_DIR.resolve("blobs")));
        // Far longer than this class can run: every assertion below is about the
        // very next request after an API call, so a pass can only mean the API
        // made the change effective and never that a cache happened to lapse.
        registry.add("megarepo.firewall.enforcement.settings-refresh-interval", () -> "10m");
        // The observation path's per-node shortcut would otherwise skip the second
        // look at a path it has already seen, and this class downloads the same
        // path repeatedly on purpose.
        registry.add("megarepo.firewall.audit.reevaluation-interval", () -> "0s");
        // In production the same component blocked twice within a day writes one
        // row, so the audit log stays readable. Here the second download's row IS
        // the assertion — "the exemption let it through" is a statement about the
        // second request — and the suppression window would silently answer it
        // with the first request's row.
        registry.add("megarepo.firewall.audit.suppression-window", () -> "0s");
        // The facts resolver reads package metadata from upstream registries. This
        // test states every fact itself, in the table the request path reads, so
        // the resolver would only add a network call and a race: a row this class
        // seeded as "published four minutes ago" could be overwritten mid-method.
        registry.add("megarepo.firewall.facts.enabled", () -> "false");
    }

    @LocalServerPort private int port;

    @Autowired private TestRestTemplate rest;
    @Autowired private FirewallEnforcementSettingsService enforcementSettings;
    @Autowired private FirewallEnforcementSettingsJpaRepository enforcementRows;
    @Autowired private FirewallRepositoryConfigJpaRepository firewallConfigs;
    @Autowired private FirewallPolicyJpaRepository policies;
    @Autowired private FirewallPolicyRuleJpaRepository policyRules;
    @Autowired private FirewallQuarantineJpaRepository quarantineRows;
    @Autowired private FirewallExemptionJpaRepository exemptions;
    @Autowired private FirewallComponentFactsJpaRepository componentFacts;
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
     * <p>The only writes here that are not fixture are the enforcement row and
     * {@code enforcing_since}: the watermark is deliberately never reset at
     * runtime, so without this every method after the first would inherit the
     * first one's and nothing would ever count as freshly published.
     */
    @BeforeEach
    void freshInstallation() {
        violations.deleteAllInBatch();
        quarantineRows.deleteAllInBatch();
        exemptions.deleteAllInBatch();
        firewallConfigs.deleteAllInBatch();
        componentFacts.deleteAllInBatch();
        assets.deleteAllInBatch();
        components.deleteAllInBatch();
        affected.deleteAllInBatch();
        advisories.deleteAllInBatch();
        deleteTestPolicies();

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
    }

    // ── The hold, the queue, and the way out of it ───────────────────────────

    @Test
    @DisplayName("a component that is too new is held with a stated reason, counts every "
            + "further request, and is served again the moment an operator releases it")
    void theHeldComponentIsQueuedAndAReleaseServesItAgain() {
        String artifact = "held";

        publishBeforeAndAfterTheWatermark(artifact);
        givenPublishedMinutesAgo(artifact, PRE_EXISTING);
        givenPublishedMinutesAgo(artifact, FRESH);
        givenPolicyForThisRepository("E2E MIN_AGE", FirewallFailMode.FAIL_OPEN, minimumAge());
        quarantineRepository();

        // ── 1. Held, not refused: the answer says which and until when ────────
        ResponseEntity<String> held = download(artifact, FRESH);

        assertThat(held.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(held.getHeaders().getFirst("X-MegaRepo-Firewall"))
                .as("the machine-readable 'this was the firewall' marker is one value, "
                        + "whatever kind of refusal it was")
                .isEqualTo("blocked");
        assertThat(held.getHeaders().getFirst("X-MegaRepo-Firewall-Quarantine"))
                .as("the kind lives here, and this is the header a CI plugin would key on "
                        + "to tell 'wait' apart from 'change your dependency'")
                .startsWith(FirewallQuarantineState.QUARANTINED + ":"
                        + FirewallQuarantineReason.MIN_AGE_NOT_MET)
                .contains(";next=");
        assertThat(held.getBody())
                .as("a developer reads this in a build log; 'held' plus a time is the "
                        + "difference between waiting and opening a ticket")
                .contains("this download is being held")
                .contains(artifact + "@" + FRESH)
                .contains("MIN_AGE")
                .contains("newer than this repository's policy allows")
                .contains("Next check")
                .contains("released as soon as the reason above no longer applies");

        // ── 2. The queue entry is what makes the hold reviewable ──────────────
        // Written on the request path, inside the evaluation the response waited
        // for — so unlike the violation row this one needs no polling.
        FirewallQuarantineEntity entry = quarantineRowFor(artifact, FRESH).orElseThrow(
                () -> new AssertionError("no quarantine entry for " + artifact + "@" + FRESH));
        assertThat(entry.getState()).isEqualTo(FirewallQuarantineState.QUARANTINED);
        assertThat(entry.getReasonCode()).isEqualTo(FirewallQuarantineReason.MIN_AGE_NOT_MET);
        assertThat(entry.getNextEvaluationAt())
                .as("an entry with no next look would be a queue item nobody and nothing "
                        + "will ever come back to")
                .isNotNull();
        assertThat(entry.getRepositoryName()).isEqualTo(REPOSITORY);

        // ── 3. Asking again counts, and does not queue the same thing twice ───
        assertThat(download(artifact, FRESH).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(download(artifact, FRESH).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(quarantineRowsFor(artifact, FRESH))
                .as("one entry per component and repository — the hit count is how an "
                        + "operator sees which held component is actually hurting")
                .hasSize(1);
        assertThat(awaitHitCountAtLeast(entry.getId(), 3))
                .as("every refused request is counted, not only the one that created the entry")
                .isGreaterThanOrEqualTo(3);

        // ── 4. What was already in the repository is served throughout ────────
        assertPreExistingIsServedAndRecorded(artifact);

        // ── 5. The operator releases it, through the button the UI calls ──────
        // Deliberately not QuarantineService.release: the seam this class exists
        // to prove is the one between the operator's action and the next request,
        // and a service call skips exactly that. The rule has not changed — the
        // component is still far too new — so the only thing that can make the
        // next download succeed is the release being read back.
        ResponseEntity<FirewallQuarantineEntryXO> released = release(entry.getId());
        assertThat(released.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(released.getBody()).isNotNull();
        assertThat(released.getBody().state()).isEqualTo(FirewallQuarantineState.RELEASED);

        assertThat(download(artifact, FRESH).getStatusCode())
                .as("a release is somebody's decision; the very next download has to honour "
                        + "it rather than re-derive a verdict that has not changed")
                .isEqualTo(HttpStatus.OK);
    }

    // ── What is never queued ─────────────────────────────────────────────────

    @Test
    @DisplayName("a critical advisory is refused outright and produces no quarantine entry")
    void aCriticalAdvisoryIsRefusedWithoutBeingQueued() {
        String artifact = "critical";

        givenCriticalAdvisoryFor(artifact);
        publishBeforeAndAfterTheWatermark(artifact);
        givenPolicyForThisRepository("E2E CVSS", FirewallFailMode.FAIL_OPEN, cvssThreshold());
        quarantineRepository();

        ResponseEntity<String> refused = download(artifact, FRESH);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(refused.getBody())
                .as("blocked, not held — and the body must not offer to wait")
                .contains("this download was blocked")
                .contains("CVSS_THRESHOLD")
                .contains(ADVISORY_ID)
                .doesNotContain("is being held");
        assertThat(refused.getHeaders().getFirst("X-MegaRepo-Firewall-Quarantine"))
                .as("nothing is being held, so there is nothing to say about a hold")
                .isNull();

        assertThat(quarantineRows.findAll())
                .as("design §5.1: only verdicts that resolve by themselves are queued. A "
                        + "queue that also fills with things nobody will ever release stops "
                        + "being read, and a release button next to a critical advisory is an "
                        + "invitation")
                .isEmpty();

        assertPreExistingIsServedAndRecorded(artifact);
    }

    @Test
    @DisplayName("when a refusing rule and a holding rule both match, the refusal wins and "
            + "nothing is queued")
    void anOutrightRefusalWinsOverAHold() {
        String artifact = "critical-and-new";

        givenCriticalAdvisoryFor(artifact);
        publishBeforeAndAfterTheWatermark(artifact);
        givenPublishedMinutesAgo(artifact, FRESH);
        givenPolicyForThisRepository(
                "E2E CVSS and MIN_AGE", FirewallFailMode.FAIL_OPEN, minimumAge(), cvssThreshold());
        quarantineRepository();

        ResponseEntity<String> refused = download(artifact, FRESH);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(refused.getBody())
                .as("both rules matched, and the one that cannot be waited out decides")
                .contains("this download was blocked")
                .contains("CVSS_THRESHOLD");
        assertThat(quarantineRows.findAll())
                .as("a component that is both malicious-by-advisory and merely young must not "
                        + "end up in a queue, where waiting or a release would make it "
                        + "servable — the refusal is the whole answer")
                .isEmpty();
    }

    // ── Exemptions ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("an approved exemption serves the component instead of holding it, and the "
            + "violation row names the exemption that did it")
    void anApprovedExemptionServesTheComponentAndTheLogNamesIt() {
        String artifact = "exempted";

        publishBeforeAndAfterTheWatermark(artifact);
        givenPublishedMinutesAgo(artifact, FRESH);
        givenPolicyForThisRepository("E2E MIN_AGE exempt", FirewallFailMode.FAIL_OPEN, minimumAge());
        quarantineRepository();

        // Requested and approved over HTTP, the way an operator would, and scoped
        // to this repository and to MIN_AGE alone. COMPONENT scope so the key is
        // the version-less purl: it is the form the API stores for that scope and
        // the one the request path compares against, neither of which depends on
        // which qualifiers the format module put on this particular artifact.
        UUID exemption = givenApprovedExemption(
                artifact, FirewallExemptionScope.COMPONENT, FirewallRuleType.MIN_AGE, null);

        assertThat(download(artifact, FRESH).getStatusCode())
                .as("the operator has already decided this component may pass MIN_AGE; "
                        + "holding it anyway would deny exactly what they approved")
                .isEqualTo(HttpStatus.OK);

        assertThat(quarantineRows.findAll())
                .as("an exempted rule withholds nothing, so there is nothing to hold and "
                        + "nothing for an operator to work through")
                .isEmpty();

        FirewallViolationEntity row = awaitEnforcementViolationFor(
                artifact, FRESH, violation -> Boolean.TRUE.equals(
                        violation.getRequestContext().get("exempted")));
        assertThat(row.getRequestContext())
                .as("'a BLOCK rule matched and the download went out anyway' is not an audit "
                        + "trail on its own — the row has to name whose decision that was")
                .containsEntry("rule", FirewallRuleType.MIN_AGE.name())
                .containsEntry("exempted", true)
                .containsEntry("exemptionId", exemption.toString())
                .containsEntry("blocked", false)
                .containsEntry("decision", "EXEMPTED");
    }

    @Test
    @DisplayName("an exemption scoped to another rule does not cover this one")
    void anExemptionForAnotherRuleDoesNotApply() {
        String artifact = "exempted-elsewhere";

        publishBeforeAndAfterTheWatermark(artifact);
        givenPublishedMinutesAgo(artifact, FRESH);
        givenPolicyForThisRepository("E2E MIN_AGE narrow", FirewallFailMode.FAIL_OPEN, minimumAge());
        quarantineRepository();

        givenApprovedExemption(
                artifact, FirewallExemptionScope.COMPONENT, FirewallRuleType.KNOWN_MALICIOUS, null);

        assertThat(download(artifact, FRESH).getStatusCode())
                .as("'exempt from KNOWN_MALICIOUS but not from MIN_AGE' has to be expressible, "
                        + "or every exemption is a blanket pass and nobody dares grant one")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(quarantineRowFor(artifact, FRESH))
                .as("the rule that was not exempted did what it always does")
                .isPresent();
    }

    @Test
    @DisplayName("an exemption that has lapsed blocks again")
    void anExpiredExemptionBlocksAgain() {
        String artifact = "exemption-lapsed";

        publishBeforeAndAfterTheWatermark(artifact);
        givenPublishedMinutesAgo(artifact, FRESH);
        givenPolicyForThisRepository("E2E MIN_AGE expiring", FirewallFailMode.FAIL_OPEN, minimumAge());
        quarantineRepository();

        UUID exemption = givenApprovedExemption(
                artifact,
                FirewallExemptionScope.COMPONENT,
                FirewallRuleType.MIN_AGE,
                Instant.now().plus(Duration.ofDays(30)));

        assertThat(download(artifact, FRESH).getStatusCode())
                .as("live, so it applies")
                .isEqualTo(HttpStatus.OK);

        // Moving the row's clock rather than waiting thirty days, and rather than
        // asking the API: it refuses an expiry in the past on purpose, because
        // through the API that would mean creating an exemption that never
        // applied. What is being asserted is the other thing — one that *did*
        // apply and has since lapsed.
        //
        // The state is left APPROVED deliberately. The daily sweep is what flips
        // it to EXPIRED, and the property worth having is that an exemption stops
        // covering a download the moment it lapses, not when a cron job next
        // notices.
        expireNow(exemption);

        assertThat(download(artifact, FRESH).getStatusCode())
                .as("an exemption that does not stop is a whitelist entry nobody dares "
                        + "delete; stopping is the single behaviour that makes it different")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exemptions.findById(exemption).orElseThrow().getState())
                .as("still APPROVED in the table — the expiry sweep has not run, and the "
                        + "download was refused anyway")
                .isEqualTo(FirewallExemptionState.APPROVED);
        assertThat(quarantineRowFor(artifact, FRESH))
                .as("with nothing covering it any more, MIN_AGE holds it like any other "
                        + "component that is too new")
                .isPresent();
    }

    @Test
    @DisplayName("the component key the 403 prints is the key an exemption has to carry")
    void theBlockBodyNamesTheKeyAnExemptionHasToCarry() {
        String artifact = "self-service";

        givenCriticalAdvisoryFor(artifact);
        publishBeforeAndAfterTheWatermark(artifact);
        givenPolicyForThisRepository("E2E CVSS self-service", FirewallFailMode.FAIL_OPEN, cvssThreshold());
        quarantineRepository();

        ResponseEntity<String> refused = download(artifact, FRESH);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // The developer's actual next step: the body says which component was
        // refused, which rule refused it, and where to ask. This walks that path
        // literally — the request is filed with the string the body printed, at
        // VERSION scope, so nothing but the block body decided what it names.
        String componentKey = componentKeyFrom(refused.getBody());
        assertThat(refused.getBody())
                .as("and the same body has to say where to send the request, with the three "
                        + "fields it will not accept without")
                .contains("To ask for an exemption")
                .contains("/api/v1/firewall/exemptions with componentKey=" + componentKey)
                .contains("ruleType=" + FirewallRuleType.CVSS_THRESHOLD)
                .contains("repositoryId=" + repositoryId);

        UUID exemption = approve(request(new FirewallExemptionRequestXO(
                componentKey,
                FirewallExemptionScope.VERSION,
                repositoryId,
                FirewallRuleType.CVSS_THRESHOLD,
                List.of(),
                null,
                "Reviewed with the security team; this version is not reachable from our code.")),
                null);

        assertThat(download(artifact, FRESH).getStatusCode())
                .as("a 403 that names a key no exemption can be built from would send every "
                        + "developer to the administrator instead")
                .isEqualTo(HttpStatus.OK);
        assertThat(awaitEnforcementViolationFor(artifact, FRESH,
                violation -> Boolean.TRUE.equals(violation.getRequestContext().get("exempted")))
                .getRequestContext())
                .containsEntry("exemptionId", exemption.toString());
    }

    // ── When a rule cannot decide ────────────────────────────────────────────

    @Test
    @DisplayName("a rule that cannot decide holds the component under FAIL_CLOSED")
    void anUndecidableRuleHoldsTheComponentUnderFailClosed() {
        String artifact = "unresolved-closed";

        publishBeforeAndAfterTheWatermark(artifact);
        // No publication date for either version: MIN_AGE cannot judge them. In
        // production this is the ordinary state of a component the background
        // resolver has not reached yet, which is why it is not treated as a fault.
        givenFactsUnresolved(artifact, PRE_EXISTING);
        givenFactsUnresolved(artifact, FRESH);
        givenPolicyForThisRepository("E2E MIN_AGE fail-closed", FirewallFailMode.FAIL_CLOSED, minimumAge());
        quarantineRepository();

        ResponseEntity<String> held = download(artifact, FRESH);

        assertThat(held.getStatusCode())
                .as("this repository is configured to deny what it cannot check")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(held.getBody())
                .contains("this download is being held")
                .contains("still missing a fact it needs to judge it");

        FirewallQuarantineEntity entry = quarantineRowFor(artifact, FRESH).orElseThrow(
                () -> new AssertionError("fail-closed indecision left nothing in the queue"));
        assertThat(entry.getReasonCode())
                .as("held for a reason that resolves itself when the facts arrive — not "
                        + "recorded as a policy violation, which nothing would ever undo")
                .isEqualTo(FirewallQuarantineReason.EVALUATION_INCOMPLETE);
        assertThat(entry.getState()).isEqualTo(FirewallQuarantineState.QUARANTINED);

        assertThat(awaitEnforcementViolationFor(artifact, FRESH).getRequestContext())
                .as("a refusal whose reason never reached the log is the case where the "
                        + "record matters most: 'we could not tell' has to be as auditable "
                        + "as 'this advisory says so'")
                .containsEntry("rule", FirewallRuleType.MIN_AGE.name())
                .containsEntry("quarantined", true)
                .containsEntry("quarantineReason", FirewallQuarantineReason.EVALUATION_INCOMPLETE.name());

        assertPreExistingIsServedAndRecorded(artifact);
    }

    @Test
    @DisplayName("the same undecidable rule serves the component under FAIL_OPEN")
    void anUndecidableRuleServesTheComponentUnderFailOpen() {
        String artifact = "unresolved-open";

        publishBeforeAndAfterTheWatermark(artifact);
        givenFactsUnresolved(artifact, FRESH);
        givenPolicyForThisRepository("E2E MIN_AGE fail-open", FirewallFailMode.FAIL_OPEN, minimumAge());
        quarantineRepository();

        assertThat(download(artifact, FRESH).getStatusCode())
                .as("same rule, same missing fact, opposite fail mode — a firewall that "
                        + "cannot check something must not break every build by default")
                .isEqualTo(HttpStatus.OK);
        assertThat(quarantineRows.findAll())
                .as("fail-open serves it, so there is nothing to come back to later")
                .isEmpty();
        assertThat(awaitEnforcementViolationFor(artifact, FRESH).getRequestContext())
                .as("served is not the same as unnoticed: the operator deciding whether to "
                        + "move this repository to fail-closed needs to see how often the "
                        + "firewall could not tell")
                .containsEntry("rule", FirewallRuleType.MIN_AGE.name())
                .containsEntry("blocked", false)
                .containsEntry("quarantined", false);
    }

    // ── Fixture: publishing ──────────────────────────────────────────────────

    /** Steps 1 to 3 of the fixture order documented on the class. */
    private void publishBeforeAndAfterTheWatermark(String artifact) {
        upload(artifact, PRE_EXISTING);
        armMasterSwitch();
        upload(artifact, FRESH);
    }

    /**
     * The grandfathering rule, asserted rather than worked around.
     *
     * <p>Called at the end of the methods whose rule would otherwise deny this
     * version too: it trips exactly the same rule as {@link #FRESH} and is served
     * because it was already in the repository when the switch was flipped.
     */
    private void assertPreExistingIsServed(String artifact) {
        assertThat(download(artifact, PRE_EXISTING).getStatusCode())
                .as("arming enforcement may not break a build that depends on something "
                        + "already stored — the customer's hardest constraint")
                .isEqualTo(HttpStatus.OK);
        assertThat(quarantineRowFor(artifact, PRE_EXISTING))
                .as("and it is not quietly queued either: a queue entry for a component that "
                        + "is being served is an operator task with no purpose")
                .isEmpty();
    }

    /**
     * The same, plus the audit trail a matched rule leaves behind.
     *
     * <p>Separate from {@link #assertPreExistingIsServed} because the row exists
     * only where the evaluation had something to say about a rule at all — which
     * includes a rule that could not decide, but not a component no rule ever
     * looked at.
     */
    private void assertPreExistingIsServedAndRecorded(String artifact) {
        assertPreExistingIsServed(artifact);
        assertThat(awaitEnforcementViolationFor(artifact, PRE_EXISTING).getRequestContext())
                .as("recorded as a finding, so an operator can see what arming would have "
                        + "cost — but not as a block")
                .containsEntry("preExisting", true)
                .containsEntry("blocked", false);
    }

    private void upload(String artifact, String version) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        ResponseEntity<String> response = admin().exchange(
                url("/repository/" + REPOSITORY + "/" + path(artifact, version)),
                HttpMethod.PUT,
                new HttpEntity<>(ARTIFACT, headers),
                String.class);
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("publishing %s@%s failed: %s", artifact, version, response.getBody())
                .isTrue();
    }

    /**
     * Downloads anonymously, the way Maven's transport does: no JSON in
     * {@code Accept}, so a refusal comes back as the plain-text body a build log
     * can show.
     */
    private ResponseEntity<String> download(String artifact, String version) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, MediaType.ALL_VALUE);
        return rest.exchange(
                url("/repository/" + REPOSITORY + "/" + path(artifact, version)),
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class);
    }

    private static String path(String artifact, String version) {
        return "com/acme/%s/%s/%s-%s.jar".formatted(artifact, version, artifact, version);
    }

    /** The qualifier-free purl the facts table is keyed on. */
    private static String coordinates(String artifact, String version) {
        return "pkg:maven/%s/%s@%s".formatted(GROUP_ID, artifact, version);
    }

    // ── Fixture: the administration API ──────────────────────────────────────

    private void armMasterSwitch() {
        ResponseEntity<String> armed = admin().exchange(
                url("/api/v1/admin/firewall/enforcement"),
                HttpMethod.PUT,
                json(new FirewallEnforcementUpdateXO(true, "ENABLE ENFORCEMENT")),
                String.class);
        assertThat(armed.getStatusCode())
                .as("arming the master switch failed: %s", armed.getBody())
                .isEqualTo(HttpStatus.OK);
    }

    private void quarantineRepository() {
        ResponseEntity<FirewallRepositoryStateXO> response = admin().exchange(
                url("/api/v1/admin/firewall/repositories/" + repositoryId),
                HttpMethod.PUT,
                json(new FirewallRepositoryModeUpdateXO(
                        FirewallMode.QUARANTINE, "QUARANTINE " + REPOSITORY)),
                FirewallRepositoryStateXO.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().mode()).isEqualTo(FirewallMode.QUARANTINE);
    }

    /**
     * Creates the policy and hands it to this repository, both over HTTP.
     *
     * <p>Assigned while the repository is still observing, which is also why no
     * confirmation phrase is needed: swapping a policy under a repository that is
     * refusing downloads right now is the case the API guards, and this is not it.
     */
    private void givenPolicyForThisRepository(
            String name, FirewallFailMode failMode, FirewallPolicyRuleXO... rules) {

        ResponseEntity<FirewallPolicyXO> created = admin().exchange(
                url("/api/v1/admin/firewall/policies"),
                HttpMethod.POST,
                json(new FirewallPolicyUpsertXO(name, "End-to-end test policy", false, List.of(rules), null)),
                FirewallPolicyXO.class);
        assertThat(created.getStatusCode())
                .as("creating the policy failed")
                .isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).isNotNull();

        ResponseEntity<FirewallRepositoryStateXO> assigned = admin().exchange(
                url("/api/v1/admin/firewall/repositories/" + repositoryId + "/policy"),
                HttpMethod.PUT,
                json(new FirewallRepositoryPolicyUpdateXO(created.getBody().id(), failMode, null)),
                FirewallRepositoryStateXO.class);
        assertThat(assigned.getStatusCode())
                .as("assigning the policy failed")
                .isEqualTo(HttpStatus.OK);
    }

    private static FirewallPolicyRuleXO minimumAge() {
        return new FirewallPolicyRuleXO(
                null, FirewallRuleType.MIN_AGE, FirewallAction.BLOCK, Map.of("minAge", MIN_AGE), true, false);
    }

    private static FirewallPolicyRuleXO cvssThreshold() {
        return new FirewallPolicyRuleXO(
                null, FirewallRuleType.CVSS_THRESHOLD, FirewallAction.BLOCK,
                Map.of("minScore", 9.0), true, false);
    }

    private ResponseEntity<FirewallQuarantineEntryXO> release(UUID entryId) {
        return admin().exchange(
                url("/api/v1/admin/firewall/quarantine/" + entryId + "/release"),
                HttpMethod.POST,
                json(new FirewallQuarantineDecisionXO(
                        "Reviewed by the platform team; this release is known good.")),
                FirewallQuarantineEntryXO.class);
    }

    /** Files a request and approves it, both as an {@code nx-admin} over HTTP. */
    private UUID givenApprovedExemption(
            String artifact, FirewallExemptionScope scope, FirewallRuleType ruleType, Instant expiresAt) {

        return approve(request(new FirewallExemptionRequestXO(
                coordinates(artifact, FRESH),
                scope,
                repositoryId,
                ruleType,
                List.of(),
                null,
                "Signed off for the end-to-end test of the firewall exemption path.")),
                expiresAt);
    }

    private UUID request(FirewallExemptionRequestXO body) {
        ResponseEntity<FirewallExemptionXO> requested = admin().exchange(
                url("/api/v1/firewall/exemptions"), HttpMethod.POST, json(body), FirewallExemptionXO.class);
        assertThat(requested.getStatusCode())
                .as("filing the exemption request failed")
                .isEqualTo(HttpStatus.CREATED);
        assertThat(requested.getBody()).isNotNull();
        return requested.getBody().id();
    }

    private UUID approve(UUID exemptionId, Instant expiresAt) {
        ResponseEntity<FirewallExemptionXO> approved = admin().exchange(
                url("/api/v1/firewall/exemptions/" + exemptionId + "/approve"),
                HttpMethod.POST,
                json(new FirewallExemptionDecisionXO(expiresAt, "Approved for the test scenario.")),
                FirewallExemptionXO.class);
        assertThat(approved.getStatusCode())
                .as("approving the exemption failed")
                .isEqualTo(HttpStatus.OK);
        assertThat(approved.getBody()).isNotNull();
        assertThat(approved.getBody().state()).isEqualTo(FirewallExemptionState.APPROVED);
        return approved.getBody().id();
    }

    // ── Fixture: the tables the background tasks would fill ──────────────────

    /**
     * States that this version was published minutes ago.
     *
     * <p>Written straight to {@code firewall_component_facts} because that is the
     * table the request path reads; in production a background resolver fills it
     * from package metadata, which is exactly the network call the request path is
     * forbidden to make.
     */
    private void givenPublishedMinutesAgo(String artifact, String version) {
        givenFacts(artifact, version, FirewallFactsState.RESOLVED,
                Instant.now().minus(Duration.ofMinutes(4)));
    }

    /** States that nothing is known yet — the input a rule answers INDETERMINATE to. */
    private void givenFactsUnresolved(String artifact, String version) {
        givenFacts(artifact, version, FirewallFactsState.UNKNOWN, null);
    }

    private void givenFacts(
            String artifact, String version, FirewallFactsState state, Instant publishedAt) {

        FirewallComponentFactsEntity facts = new FirewallComponentFactsEntity();
        facts.setPurl(coordinates(artifact, version));
        facts.setPurlType("maven");
        facts.setState(state);
        facts.setPublishedAt(publishedAt);
        facts.setDeclaredLicenses(new String[0]);
        facts.setSource("end-to-end-test");
        facts.setFetchedAt(publishedAt == null ? null : Instant.now());
        facts.setCreatedAt(Instant.now());
        facts.setUpdatedAt(Instant.now());
        componentFacts.saveAndFlush(facts);
    }

    /** One advisory, CVSS 10.0, covering both versions of this artifact. */
    private void givenCriticalAdvisoryFor(String artifact) {
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
        range.setPurlNamespace(GROUP_ID);
        range.setPurlName(artifact);
        range.setVersionRange(">=0.1.0, <2.0.0");
        range.setIntroduced("0.1.0");
        range.setFixed("2.0.0");
        affected.saveAndFlush(range);
    }

    /**
     * Backdates an approved exemption's expiry.
     *
     * <p>The one thing here the API will not do, and refuses for a good reason:
     * see {@link #anExpiredExemptionBlocksAgain()}.
     */
    private void expireNow(UUID exemptionId) {
        FirewallExemptionEntity entity = exemptions.findById(exemptionId).orElseThrow();
        entity.setExpiresAt(Instant.now().minus(Duration.ofMinutes(1)));
        entity.setUpdatedAt(Instant.now());
        exemptions.saveAndFlush(entity);
    }

    // ── Reading what the firewall wrote ──────────────────────────────────────

    private Optional<FirewallQuarantineEntity> quarantineRowFor(String artifact, String version) {
        List<FirewallQuarantineEntity> rows = quarantineRowsFor(artifact, version);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private List<FirewallQuarantineEntity> quarantineRowsFor(String artifact, String version) {
        return quarantineRows.findAll().stream()
                .filter(row -> row.getComponentKey() != null
                        && row.getComponentKey().contains("/" + artifact + "@" + version))
                .toList();
    }

    /**
     * The hit count, polled.
     *
     * <p>{@code recordHit} is fire-and-forget by design — a download that has
     * already been refused must not wait for a counter — so the number can lag the
     * response it belongs to.
     */
    private long awaitHitCountAtLeast(UUID entryId, long expected) {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        long seen = 0;
        while (System.nanoTime() < deadline) {
            seen = quarantineRows.findById(entryId).map(FirewallQuarantineEntity::getHitCount).orElse(0L);
            if (seen >= expected) {
                return seen;
            }
            sleep();
        }
        return seen;
    }

    private FirewallViolationEntity awaitEnforcementViolationFor(String artifact, String version) {
        return awaitEnforcementViolationFor(artifact, version, row -> true);
    }

    /**
     * The row the <em>enforcement</em> path wrote for this version.
     *
     * <p>Two paths write to {@code firewall_violation} and both can have a row for
     * the same component: the observation path records "these advisories name it"
     * ({@code phase=audit}) and the enforcement path records what the policy
     * concluded ({@code phase=enforcement}). Only the second says whether the
     * download was denied.
     *
     * <p>Polled because the verdict is given before the row is written — the client
     * must not wait for the audit trail.
     */
    private FirewallViolationEntity awaitEnforcementViolationFor(
            String artifact, String version, Predicate<FirewallViolationEntity> matching) {

        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            Optional<FirewallViolationEntity> row = violations.findAll().stream()
                    .filter(candidate -> candidate.getPurl() != null
                            && candidate.getPurl().contains("/" + artifact + "@" + version))
                    .filter(candidate -> candidate.getRequestContext() != null
                            && "enforcement".equals(candidate.getRequestContext().get("phase")))
                    .filter(matching)
                    .findFirst();
            if (row.isPresent()) {
                return row.get();
            }
            sleep();
        }
        throw new AssertionError("The enforcement path recorded no matching row for "
                + artifact + "@" + version + "; rows: " + violations.findAll().stream()
                        .map(row -> row.getPurl() + " " + row.getRequestContext())
                        .toList());
    }

    /**
     * The component key out of the plain-text 403, read the way a developer would:
     * off the {@code Component} line.
     */
    private static String componentKeyFrom(String body) {
        assertThat(body).isNotNull();
        for (String line : body.split("\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equals("Component")) {
                return line.substring(colon + 1).trim();
            }
        }
        throw new AssertionError("The block body named no component:\n" + body);
    }

    // ── Fixture: the installation ────────────────────────────────────────────

    /**
     * The repository row outlives the per-method purge on purpose. The violation
     * recorder writes off the request thread, so a row from the previous method can
     * still be in flight; deleting the repository under it turns that into a
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

    /**
     * Removes the policies this class created, leaving V16's seeded default alone:
     * something has to be the default, and deleting it would test a state no
     * installation is ever in.
     */
    private void deleteTestPolicies() {
        List<FirewallPolicyEntity> mine = policies.findAll().stream()
                .filter(policy -> !policy.isDefault())
                .filter(policy -> policy.getName() != null && policy.getName().startsWith("E2E "))
                .toList();
        for (FirewallPolicyEntity policy : mine) {
            policyRules.deleteAll(policyRules.findByPolicyId(policy.getId()));
        }
        policies.deleteAll(mine);
        policies.flush();
    }

    // ── Plumbing ─────────────────────────────────────────────────────────────

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
            throw new IllegalStateException("Interrupted while waiting for a firewall row", e);
        }
    }

    private static Path createWorkDir() {
        try {
            return Files.createTempDirectory("megarepo-firewall-quarantine-e2e");
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
