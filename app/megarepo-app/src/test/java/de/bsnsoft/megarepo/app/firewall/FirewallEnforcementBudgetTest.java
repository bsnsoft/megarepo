package de.bsnsoft.megarepo.app.firewall;

import com.github.packageurl.PackageURL;
import de.bsnsoft.megarepo.app.MegaRepoApplication;
import de.bsnsoft.megarepo.core.firewall.FirewallAction;
import de.bsnsoft.megarepo.core.firewall.FirewallMode;
import de.bsnsoft.megarepo.core.firewall.FirewallQuarantineReason;
import de.bsnsoft.megarepo.core.firewall.FirewallQuarantineState;
import de.bsnsoft.megarepo.core.firewall.FirewallRuleType;
import de.bsnsoft.megarepo.core.repository.RepositoryType;
import de.bsnsoft.megarepo.database.entity.AssetEntity;
import de.bsnsoft.megarepo.database.entity.ComponentEntity;
import de.bsnsoft.megarepo.database.entity.FirewallEnforcementSettingsEntity;
import de.bsnsoft.megarepo.database.entity.FirewallQuarantineEntity;
import de.bsnsoft.megarepo.database.entity.RepositoryEntity;
import de.bsnsoft.megarepo.database.repository.AdvisoryAffectedJpaRepository;
import de.bsnsoft.megarepo.database.repository.AdvisoryJpaRepository;
import de.bsnsoft.megarepo.database.repository.AssetJpaRepository;
import de.bsnsoft.megarepo.database.repository.ComponentJpaRepository;
import de.bsnsoft.megarepo.database.repository.FirewallEnforcementSettingsJpaRepository;
import de.bsnsoft.megarepo.database.repository.FirewallQuarantineJpaRepository;
import de.bsnsoft.megarepo.database.repository.FirewallRepositoryConfigJpaRepository;
import de.bsnsoft.megarepo.database.repository.FirewallViolationJpaRepository;
import de.bsnsoft.megarepo.database.repository.RepositoryJpaRepository;
import de.bsnsoft.megarepo.repository.firewall.FirewallEnforcementService;
import de.bsnsoft.megarepo.repository.firewall.FirewallEnforcementSettingsService;
import de.bsnsoft.megarepo.repository.firewall.FirewallEvaluation;
import de.bsnsoft.megarepo.repository.firewall.FirewallRequestContext;
import de.bsnsoft.megarepo.repository.firewall.identity.PurlBuilder;
import de.bsnsoft.megarepo.repository.firewall.rule.corpus.ComponentCorpusService;
import de.bsnsoft.megarepo.repository.firewall.rule.corpus.ComponentNameCorpus;
import de.bsnsoft.megarepo.rest.dto.firewall.FirewallEnforcementUpdateXO;
import de.bsnsoft.megarepo.rest.dto.firewall.FirewallPolicyRuleXO;
import de.bsnsoft.megarepo.rest.dto.firewall.FirewallPolicyUpsertXO;
import de.bsnsoft.megarepo.rest.dto.firewall.FirewallPolicyXO;
import de.bsnsoft.megarepo.rest.dto.firewall.FirewallRepositoryModeUpdateXO;
import de.bsnsoft.megarepo.rest.dto.firewall.FirewallRepositoryPolicyUpdateXO;
import de.bsnsoft.megarepo.rest.dto.firewall.FirewallRepositoryStateXO;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What one enforced request costs the database (osTicket #155155).
 *
 * <h2>Why a budget at all</h2>
 *
 * The firewall runs on the path of every download from an armed repository, in
 * front of a client that is usually a build. Nothing about the feature's
 * correctness stops it from growing a query per rule, a lookup per advisory or a
 * second policy read nobody notices on a laptop — and the customer's instances
 * serve artifacts to a CI fleet, where "one more indexed read" is one more read
 * times the fleet. A budget written down as an assertion is the only version of
 * that constraint that survives the next person adding a rule.
 *
 * <h2>Why query counts and not milliseconds</h2>
 *
 * A wall-clock assertion is a coin toss on a loaded CI machine: it fails when the
 * agent is busy and passes when a regression is masked by a warm cache. It also
 * measures the wrong thing — the cost this design controls is the number of round
 * trips, not how fast one of them happened to be today. Counting statements is
 * deterministic, reproducible on any machine, and it fails for exactly one
 * reason: somebody added a query.
 *
 * <h2>How the counting is done, and why not with Hibernate's own statistics</h2>
 *
 * {@code hibernate.generate_statistics} is the obvious mechanism and was tried
 * first. Its counter is global to the {@code SessionFactory} and carries no
 * notion of which thread issued what, and this application has several threads
 * that talk to the same database on their own schedule: the task scheduler sweeps
 * {@code scheduled_tasks} every minute, the corpus refreshes itself in the
 * background, and the firewall's own recorder writes the audit row after the
 * verdict has been given. Any of them landing inside a measurement window turns a
 * budget assertion into a flaky one — and a test that fails once a fortnight for
 * no reason is worse than no test.
 *
 * <p>So the count comes from a {@link StatementInspector}, which Hibernate calls
 * on the thread that is preparing the statement, and {@link QueryCounter} counts
 * only the threads a request is actually made of: the one that asked, and the
 * {@code firewall-enforce-*} pool the evaluation is handed to. Everything else
 * that happens to touch the database while the window is open is ignored by
 * construction rather than by hoping it does not.
 *
 * <h2>What is measured, and why not the whole HTTP request</h2>
 *
 * Each measurement brackets {@link FirewallEnforcementService#evaluate} — the
 * exact call {@code RepositoryRouter} makes on the download path, with the
 * arguments the router passes — after a real HTTP download of the same artifact
 * has already warmed everything a request warms.
 *
 * <p>Bracketing the HTTP request instead was rejected for two reasons. It is not
 * deterministic: the router writes its audit-log row and broadcasts the activity
 * event <em>after</em> the response has been streamed, so the client can be back
 * before the server is finished and the number depends on who wins. And it would
 * be dominated by queries this work package neither owns nor can hold to a
 * budget — the repository lookup, the format handler's own reads, the NVD
 * firewall, the download audit — so a regression in the firewall's own cost would
 * be a rounding error inside it. The budget below is the firewall's, stated as
 * such.
 */
@SpringBootTest(
        classes = {
            MegaRepoApplication.class,
            FirewallEnforcementBudgetTest.CountingStatements.class
        },
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FirewallEnforcementBudgetTest {

    private static final String DB_USER = "megarepo";
    private static final String DB_PASSWORD = "megarepo";

    private static final String ADMIN = "admin";
    private static final String ADMIN_PASSWORD = "admin123";

    /** Where artifacts are published, and the repository the budget is measured on. */
    private static final String HOSTED = "maven-budget-e2e";

    /**
     * A proxy in front of nothing, holding one cached artifact.
     *
     * <p>Needed because {@code TYPOSQUAT} judges only what arrived from upstream:
     * a package somebody here published under a name they chose is not squatting
     * anything. It is the rule that reads the name corpus, so it is the rule the
     * "no query on the request path" claim has to be tested through.
     */
    private static final String PROXY = "maven-budget-e2e-proxy";

    /**
     * The budget for a warm, enforced, clean download, and what it is spent on:
     *
     * <ol>
     *   <li>{@code firewall_repository_config} — this repository's mode, policy
     *       and fail mode. Read per request on purpose: an operator disarming a
     *       repository mid-incident must not wait for a cache;</li>
     *   <li>{@code assets} — the row behind the path;</li>
     *   <li>{@code components} — what that asset is;</li>
     *   <li>{@code advisory_affected} by purl coordinates — the advisories naming
     *       this package;</li>
     *   <li>{@code advisory_affected} by CPE-derived product name — the second
     *       pass that catches advisories published without a purl;</li>
     *   <li>{@code firewall_quarantine} — is this component already held? The
     *       short-circuit;</li>
     *   <li>{@code firewall_component_facts} — the locally mirrored publication
     *       date and licences. Never fetched on this thread; a miss is a miss;</li>
     *   <li>{@code firewall_policy} — which policy governs this repository;</li>
     *   <li>{@code firewall_policy_rule} — its rules, in one read regardless of
     *       how many there are.</li>
     * </ol>
     *
     * <p>Nine, and the measured number is nine: the budget is the itemisation,
     * not a round number with headroom in it. Every one of them is a local
     * indexed read, and the list is deliberately flat — no query here is
     * per-rule, per-advisory or per-finding, which is the property that keeps the
     * cost of a policy independent of its size. An exemption lookup is
     * <em>not</em> in the budget because a clean component never reaches one:
     * only a rule that actually matched and would deny asks whether an exemption
     * covers it.
     *
     * <p>The assertion is {@code <=} rather than {@code ==} so that removing a
     * query is not a test failure. Adding one is.
     */
    private static final int CLEAN_DOWNLOAD_BUDGET = 9;

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
        registry.add("megarepo.security.jwt.secret", () -> "firewall-budget-end-to-end-test-secret");
        registry.add("megarepo.data-directory", () -> directory(WORK_DIR.resolve("data")));
        registry.add("megarepo.blob-stores.default-path", () -> directory(WORK_DIR.resolve("blobs")));
        registry.add("megarepo.firewall.enforcement.settings-refresh-interval", () -> "10m");
        registry.add("megarepo.firewall.audit.reevaluation-interval", () -> "0s");
        // The corpus is published by the test rather than scanned, so the
        // background rebuild must not be due during a measurement — not because
        // it would be counted (it runs on its own thread and is filtered out),
        // but because it would replace the snapshot the assertion is about.
        registry.add("megarepo.firewall.corpus.refresh-interval", () -> "60m");
    }

    /**
     * Puts {@link QueryCounter} in front of every statement Hibernate prepares.
     *
     * <p>A {@code StatementInspector} rather than a wrapped {@code DataSource}:
     * it needs no proxying of the pool, it sees exactly the statements the
     * persistence layer issues — which is all the firewall issues — and Hibernate
     * calls it on the thread doing the work, which is the whole point.
     */
    @TestConfiguration
    static class CountingStatements {

        @Bean
        QueryCounter queryCounter() {
            return new QueryCounter();
        }

        @Bean
        HibernatePropertiesCustomizer countStatements(QueryCounter counter) {
            return properties -> properties.put(AvailableSettings.STATEMENT_INSPECTOR, counter);
        }
    }

    @LocalServerPort private int port;

    @Autowired private TestRestTemplate rest;
    @Autowired private QueryCounter queries;
    @Autowired private FirewallEnforcementService enforcement;
    @Autowired private FirewallEnforcementSettingsService enforcementSettings;
    @Autowired private FirewallEnforcementSettingsJpaRepository enforcementRows;
    @Autowired private FirewallRepositoryConfigJpaRepository firewallConfigs;
    @Autowired private FirewallViolationJpaRepository violations;
    @Autowired private FirewallQuarantineJpaRepository quarantineEntries;
    @Autowired private RepositoryJpaRepository repositories;
    @Autowired private ComponentJpaRepository components;
    @Autowired private AssetJpaRepository assets;
    @Autowired private AdvisoryJpaRepository advisories;
    @Autowired private AdvisoryAffectedJpaRepository affected;
    @Autowired private ComponentCorpusService corpusService;
    @Autowired private PurlBuilder purlBuilder;

    private UUID hostedId;
    private UUID proxyId;

    /**
     * A disarmed instance with no advisory data at all.
     *
     * <p>No advisories on purpose: every component below is clean, so no rule
     * matches, no violation is recorded and the audit writer — which runs on the
     * same pool as the evaluation and would otherwise land inside a measurement
     * window — has nothing to write. That is what makes the numbers below exact
     * rather than approximately right.
     */
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

        hostedId = givenRepository(HOSTED, "HOSTED");
        proxyId = givenRepository(PROXY, "PROXY");
    }

    @Test
    @DisplayName("a warm enforced download stays inside its query budget")
    void aWarmEnforcedDownloadStaysInsideItsBudget() {
        arm();
        publish("commons-lang3", "3.14.0");
        armRepository(HOSTED, hostedId);

        // Two real downloads first: the artifact is served, the switch is cached,
        // the connection pool is up and every statement has been prepared once.
        // What is measured afterwards is therefore the steady state, not the
        // first-request cost that no consumer pays twice.
        assertThat(download(HOSTED, "commons-lang3", "3.14.0").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(download(HOSTED, "commons-lang3", "3.14.0").getStatusCode()).isEqualTo(HttpStatus.OK);

        FirewallEvaluation[] verdict = new FirewallEvaluation[1];
        long spent = queries.around(() -> {
            verdict[0] = evaluate(hostedId, HOSTED, RepositoryType.HOSTED, "commons-lang3", "3.14.0");
        });

        assertThat(verdict[0].blocked())
                .as("the measurement has to be of the path it claims: an enforced, served download")
                .isFalse();
        assertThat(verdict[0].enforcementEvaluated())
                .as("and one the firewall actually decided, not one it declined to look at")
                .isTrue();
        assertThat(spent)
                .as("the budget is %d local indexed reads, itemised on CLEAN_DOWNLOAD_BUDGET. "
                        + "If this fails, the question is not 'raise the number' but 'which "
                        + "query was added, and does every enforced download have to pay it'",
                        CLEAN_DOWNLOAD_BUDGET)
                .isLessThanOrEqualTo(CLEAN_DOWNLOAD_BUDGET);
    }

    @Test
    @DisplayName("the name corpus is read from memory and never from the database")
    void theCorpusCostsTheRequestPathNothing() {
        corpusService.publish(givenCorpusOf(
                "pkg:maven/com.google.guava/guava@32.1.3-jre",
                "pkg:maven/org.apache.commons/commons-lang3@3.14.0",
                "pkg:maven/org.springframework.boot/spring-boot-starter-web@3.2.0"));

        // ── The narrow claim: reading it issues nothing ──────────────────────
        ComponentNameCorpus[] read = new ComponentNameCorpus[1];
        long spentReadingTheCorpus = queries.around(() -> {
            read[0] = corpusService.corpus();
        });

        assertThat(read[0].isEmpty())
                .as("a corpus that is empty would make the next assertion vacuous — the "
                        + "typosquat rule returns immediately on one")
                .isFalse();
        assertThat(spentReadingTheCorpus)
                .as("the corpus is a full scan of the components table. It is kept as a "
                        + "published in-memory snapshot precisely so that no request pays for "
                        + "one, and a rule that could quietly turn it into a query would put a "
                        + "table scan on every proxied download")
                .isZero();

        // ── The same claim, through the rule that actually reads it ──────────
        arm();
        UUID withoutTyposquat = givenPolicy("budget-cvss-only", FirewallRuleType.CVSS_THRESHOLD);
        UUID withTyposquat = givenPolicy(
                "budget-cvss-and-typosquat", FirewallRuleType.CVSS_THRESHOLD, FirewallRuleType.TYPOSQUAT);
        assignPolicy(PROXY, proxyId, withoutTyposquat, null);
        armRepository(PROXY, proxyId);
        givenCachedInProxy("guava", "32.1.3-jre");

        assertThat(download(PROXY, "guava", "32.1.3-jre").getStatusCode())
                .as("a proxy cache hit: served from the local blob, no upstream involved")
                .isEqualTo(HttpStatus.OK);

        long withoutTheRule = queries.around(
                () -> evaluate(proxyId, PROXY, RepositoryType.PROXY, "guava", "32.1.3-jre"));

        assignPolicy(PROXY, proxyId, withTyposquat, "CHANGE POLICY " + PROXY);
        FirewallEvaluation[] verdict = new FirewallEvaluation[1];
        long withTheRule = queries.around(() -> {
            verdict[0] = evaluate(proxyId, PROXY, RepositoryType.PROXY, "guava", "32.1.3-jre");
        });

        assertThat(verdict[0].blocked())
                .as("the corpus contains this package's own name and nothing confusable with "
                        + "it, so the rule examines the corpus and finds nothing — which is the "
                        + "case that has to be free")
                .isFalse();
        assertThat(withTheRule)
                .as("adding the corpus-reading rule to the policy added no query. Stated as a "
                        + "difference rather than as an absolute, because that is the claim: the "
                        + "heuristic's whole design rests on the request path never touching the "
                        + "components table")
                .isEqualTo(withoutTheRule);
    }

    @Test
    @DisplayName("a held component is answered for less than a full evaluation costs")
    void theQuarantineShortCircuitIsCheaperThanDecidingAgain() {
        arm();
        publish("commons-lang3", "3.14.0");
        publish("commons-io", "2.15.0");
        armRepository(HOSTED, hostedId);

        assertThat(download(HOSTED, "commons-lang3", "3.14.0").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(download(HOSTED, "commons-io", "2.15.0").getStatusCode()).isEqualTo(HttpStatus.OK);

        long fullEvaluation = queries.around(
                () -> evaluate(hostedId, HOSTED, RepositoryType.HOSTED, "commons-lang3", "3.14.0"));

        givenHeld(hostedId, HOSTED, "commons-io", "2.15.0");
        FirewallEvaluation[] verdict = new FirewallEvaluation[1];
        long shortCircuit = queries.around(() -> {
            verdict[0] = evaluate(hostedId, HOSTED, RepositoryType.HOSTED, "commons-io", "2.15.0");
        });

        assertThat(verdict[0].blocked())
                .as("the entry denies, so the download is refused — measured on the path that "
                        + "actually short-circuits, not on one that happened to be cheap")
                .isTrue();
        assertThat(shortCircuit)
                .as("the short-circuit is a performance promise as much as a correctness one: a "
                        + "component the operator is already holding is answered from its queue "
                        + "entry, without the policy, its rules or the component's facts being "
                        + "read again. Concretely it pays items 1-6 of CLEAN_DOWNLOAD_BUDGET plus "
                        + "one UPDATE for the hit counter — seven against nine — and a build "
                        + "hammering a held artifact therefore costs less per attempt, not more. "
                        + "(Full evaluation this run: %d.)", fullEvaluation)
                .isLessThan(fullEvaluation);
    }

    // ── The call the router makes ───────────────────────────────────────────

    private FirewallEvaluation evaluate(
            UUID repositoryId, String repositoryName, RepositoryType type, String artifact, String version) {
        return enforcement.evaluate(
                repositoryId,
                repositoryName,
                type,
                path(artifact, version),
                new FirewallRequestContext(
                        null, "127.0.0.1", path(artifact, version), "GET", null));
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

    private void armRepository(String name, UUID repositoryId) {
        ResponseEntity<FirewallRepositoryStateXO> response = admin().exchange(
                url("/api/v1/admin/firewall/repositories/" + repositoryId),
                HttpMethod.PUT,
                json(new FirewallRepositoryModeUpdateXO(
                        FirewallMode.QUARANTINE, "QUARANTINE " + name)),
                FirewallRepositoryStateXO.class);
        assertThat(response.getStatusCode())
                .as("arming %s failed", name)
                .isEqualTo(HttpStatus.OK);
    }

    private UUID givenPolicy(String name, FirewallRuleType... ruleTypes) {
        List<FirewallPolicyRuleXO> rules = java.util.Arrays.stream(ruleTypes)
                .map(type -> new FirewallPolicyRuleXO(
                        null, type, FirewallAction.BLOCK, configFor(type), true, false))
                .toList();
        ResponseEntity<FirewallPolicyXO> created = admin().exchange(
                url("/api/v1/admin/firewall/policies"),
                HttpMethod.POST,
                json(new FirewallPolicyUpsertXO(name, "Query-budget fixture.", false, rules, null)),
                FirewallPolicyXO.class);
        assertThat(created.getStatusCode().is2xxSuccessful())
                .as("creating policy %s failed", name)
                .isTrue();
        assertThat(created.getBody()).isNotNull();
        return created.getBody().id();
    }

    /** The rule's parameters, spelled out rather than defaulted, so the API accepts them. */
    private static Map<String, Object> configFor(FirewallRuleType ruleType) {
        return ruleType == FirewallRuleType.CVSS_THRESHOLD ? Map.of("minScore", 9.0) : Map.of();
    }

    private void assignPolicy(String name, UUID repositoryId, UUID policyId, String confirmation) {
        ResponseEntity<FirewallRepositoryStateXO> response = admin().exchange(
                url("/api/v1/admin/firewall/repositories/" + repositoryId + "/policy"),
                HttpMethod.PUT,
                json(new FirewallRepositoryPolicyUpdateXO(policyId, null, confirmation)),
                FirewallRepositoryStateXO.class);
        assertThat(response.getStatusCode())
                .as("assigning a policy to %s failed", name)
                .isEqualTo(HttpStatus.OK);
    }

    // ── The repository API, over HTTP ────────────────────────────────────────

    private void publish(String artifact, String version) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.set(HttpHeaders.ACCEPT, MediaType.ALL_VALUE);
        ResponseEntity<String> response = admin().exchange(
                url("/repository/" + HOSTED + "/" + path(artifact, version)),
                HttpMethod.PUT,
                new HttpEntity<>(ARTIFACT, headers),
                String.class);
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("publishing %s %s failed: %s", artifact, version, response.getBody())
                .isTrue();
    }

    private ResponseEntity<String> download(String repository, String artifact, String version) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, MediaType.ALL_VALUE);
        return rest.exchange(
                url("/repository/" + repository + "/" + path(artifact, version)),
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class);
    }

    private static String path(String artifact, String version) {
        String group = "commons-io".equals(artifact)
                ? "commons-io"
                : "guava".equals(artifact) ? "com/google/guava" : "org/apache/commons";
        return "%s/%s/%s/%s-%s.jar".formatted(group, artifact, version, artifact, version);
    }

    // ── Fixture ─────────────────────────────────────────────────────────────

    private UUID givenRepository(String name, String type) {
        return repositories.findByName(name)
                .map(RepositoryEntity::getId)
                .orElseGet(() -> {
                    RepositoryEntity repository = new RepositoryEntity();
                    repository.setName(name);
                    repository.setFormat("maven2");
                    repository.setType(type);
                    repository.setOnline(true);
                    repository.setBlobStoreName("default");
                    repository.setAttributes(new HashMap<>());
                    repository.setCreatedAt(Instant.now());
                    repository.setUpdatedAt(Instant.now());
                    return repositories.saveAndFlush(repository).getId();
                });
    }

    /**
     * An artifact in the proxy's local cache, as a proxy that has already fetched
     * one would hold it.
     *
     * <p>Published into the hosted repository first and then attached to the
     * proxy by its blob reference: the point is a cache <em>hit</em>, so the
     * remote must never be reached, and the shortest way to guarantee that is for
     * there to be nothing to reach. The asset's age is what decides a hit, and a
     * row written now is well inside the default content TTL.
     */
    private void givenCachedInProxy(String artifact, String version) {
        publish(artifact, version);
        AssetEntity published = assets
                .findByRepositoryIdAndPath(hostedId, path(artifact, version))
                .orElseThrow(() -> new AssertionError("nothing was published for " + artifact));
        ComponentEntity source = components.findById(published.getComponentId())
                .orElseThrow(() -> new AssertionError("no component for " + artifact));

        ComponentEntity cached = new ComponentEntity();
        cached.setRepositoryId(proxyId);
        cached.setFormat(source.getFormat());
        cached.setNamespace(source.getNamespace());
        cached.setName(source.getName());
        cached.setVersion(source.getVersion());
        cached.setCreatedAt(Instant.now());
        cached.setUpdatedAt(Instant.now());
        UUID cachedComponentId = components.saveAndFlush(cached).getId();

        AssetEntity copy = new AssetEntity();
        copy.setRepositoryId(proxyId);
        copy.setPath(published.getPath());
        copy.setComponentId(cachedComponentId);
        copy.setFormat(published.getFormat());
        copy.setBlobRef(published.getBlobRef());
        copy.setContentType(published.getContentType());
        copy.setSize(published.getSize());
        copy.setChecksumMd5(published.getChecksumMd5());
        copy.setChecksumSha1(published.getChecksumSha1());
        copy.setChecksumSha256(published.getChecksumSha256());
        copy.setChecksumSha512(published.getChecksumSha512());
        copy.setCreatedAt(Instant.now());
        copy.setLastModified(Instant.now());
        copy.setUpdatedAt(Instant.now());
        assets.saveAndFlush(copy);
    }

    /** A corpus with exactly these names in it, built the way the scan builds one. */
    private ComponentNameCorpus givenCorpusOf(String... purls) {
        ComponentNameCorpus.Builder builder = ComponentNameCorpus.builder();
        for (String purl : purls) {
            try {
                builder.add(new PackageURL(purl), false, PROXY);
            } catch (Exception e) {
                throw new IllegalStateException("Not a purl: " + purl, e);
            }
        }
        return builder.scanned(purls.length).build(Instant.now());
    }

    /**
     * A component already in the quarantine queue.
     *
     * <p>Written directly rather than produced by a rule, because the subject is
     * the <em>second</em> download of a held component: what an entry that is
     * already on file costs, not what putting one there costs.
     */
    private void givenHeld(UUID repositoryId, String repositoryName, String artifact, String version) {
        ComponentEntity component = components.findAll().stream()
                .filter(row -> repositoryId.equals(row.getRepositoryId()))
                .filter(row -> version.equals(row.getVersion()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no component for " + artifact));

        FirewallQuarantineEntity entry = new FirewallQuarantineEntity();
        entry.setRepositoryId(repositoryId);
        entry.setRepositoryName(repositoryName);
        entry.setComponentKey(purlBuilder.identify(component, null).key());
        entry.setComponentId(component.getId());
        entry.setPath(path(artifact, version));
        entry.setState(FirewallQuarantineState.QUARANTINED);
        entry.setReasonCode(FirewallQuarantineReason.UNKNOWN_COMPONENT);
        entry.setEvaluation(new HashMap<>());
        entry.setFirstSeen(Instant.now());
        entry.setLastSeen(Instant.now());
        entry.setHitCount(1);
        entry.setNextEvaluationAt(Instant.now().plus(Duration.ofHours(1)));
        entry.setCreatedAt(Instant.now());
        entry.setUpdatedAt(Instant.now());
        quarantineEntries.saveAndFlush(entry);
    }

    // ── The counter ─────────────────────────────────────────────────────────

    /**
     * Counts the statements Hibernate prepares, on the threads one request is
     * made of.
     *
     * <p>The thread filter is the whole reason this is not simply
     * {@code Statistics.getPrepareStatementCount()}: the scheduler, the corpus
     * refresh and the violation recorder all talk to the same database on their
     * own schedule, and a global counter would fold whichever of them happened to
     * fire into the number. Here they are ignored because they are not the
     * measuring thread and not the enforcement pool.
     */
    static final class QueryCounter implements StatementInspector {

        /** The pool {@link FirewallEnforcementService} hands its evaluation to. */
        private static final String ENFORCEMENT_POOL = "firewall-enforce-";

        private final AtomicLong counted = new AtomicLong();
        private volatile String measuringThread;

        /** Runs the work and answers how many statements it took. */
        long around(Runnable work) {
            counted.set(0);
            measuringThread = Thread.currentThread().getName();
            try {
                work.run();
            } finally {
                measuringThread = null;
            }
            return counted.get();
        }

        @Override
        public String inspect(String sql) {
            String scope = measuringThread;
            if (scope != null) {
                String thread = Thread.currentThread().getName();
                if (scope.equals(thread) || thread.startsWith(ENFORCEMENT_POOL)) {
                    counted.incrementAndGet();
                }
            }
            return sql;
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
            return Files.createTempDirectory("megarepo-firewall-budget-e2e");
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
