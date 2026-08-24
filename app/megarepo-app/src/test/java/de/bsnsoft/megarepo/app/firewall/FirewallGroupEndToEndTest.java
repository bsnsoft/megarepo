package de.bsnsoft.megarepo.app.firewall;

import de.bsnsoft.megarepo.app.MegaRepoApplication;
import de.bsnsoft.megarepo.core.firewall.FirewallAction;
import de.bsnsoft.megarepo.core.firewall.FirewallApiPaths;
import de.bsnsoft.megarepo.core.firewall.FirewallExemptionScope;
import de.bsnsoft.megarepo.core.firewall.FirewallExemptionState;
import de.bsnsoft.megarepo.core.firewall.FirewallMode;
import de.bsnsoft.megarepo.core.firewall.FirewallQuarantineReason;
import de.bsnsoft.megarepo.core.firewall.FirewallQuarantineState;
import de.bsnsoft.megarepo.core.firewall.FirewallRuleType;
import de.bsnsoft.megarepo.database.entity.AdvisoryAffectedEntity;
import de.bsnsoft.megarepo.database.entity.AdvisoryEntity;
import de.bsnsoft.megarepo.database.entity.ComponentEntity;
import de.bsnsoft.megarepo.database.entity.FirewallEnforcementSettingsEntity;
import de.bsnsoft.megarepo.database.entity.FirewallPolicyEntity;
import de.bsnsoft.megarepo.database.entity.FirewallPolicyRuleEntity;
import de.bsnsoft.megarepo.database.entity.FirewallQuarantineEntity;
import de.bsnsoft.megarepo.database.entity.FirewallViolationEntity;
import de.bsnsoft.megarepo.database.entity.GroupMemberEntity;
import de.bsnsoft.megarepo.database.entity.RepositoryEntity;
import de.bsnsoft.megarepo.database.repository.AdvisoryAffectedJpaRepository;
import de.bsnsoft.megarepo.database.repository.AdvisoryJpaRepository;
import de.bsnsoft.megarepo.database.repository.AssetJpaRepository;
import de.bsnsoft.megarepo.database.repository.ComponentJpaRepository;
import de.bsnsoft.megarepo.database.repository.FirewallEnforcementSettingsJpaRepository;
import de.bsnsoft.megarepo.database.repository.FirewallExemptionJpaRepository;
import de.bsnsoft.megarepo.database.repository.FirewallPolicyJpaRepository;
import de.bsnsoft.megarepo.database.repository.FirewallPolicyRuleJpaRepository;
import de.bsnsoft.megarepo.database.repository.FirewallQuarantineJpaRepository;
import de.bsnsoft.megarepo.database.repository.FirewallRepositoryConfigJpaRepository;
import de.bsnsoft.megarepo.database.repository.FirewallViolationJpaRepository;
import de.bsnsoft.megarepo.database.repository.GroupMemberJpaRepository;
import de.bsnsoft.megarepo.database.repository.RepositoryJpaRepository;
import de.bsnsoft.megarepo.repository.firewall.FirewallEnforcementSettingsService;
import de.bsnsoft.megarepo.repository.firewall.identity.PurlBuilder;
import de.bsnsoft.megarepo.rest.dto.firewall.FirewallEnforcementUpdateXO;
import de.bsnsoft.megarepo.rest.dto.firewall.FirewallExemptionDecisionXO;
import de.bsnsoft.megarepo.rest.dto.firewall.FirewallExemptionRequestXO;
import de.bsnsoft.megarepo.rest.dto.firewall.FirewallExemptionXO;
import de.bsnsoft.megarepo.rest.dto.firewall.FirewallPolicyRuleXO;
import de.bsnsoft.megarepo.rest.dto.firewall.FirewallPolicyUpsertXO;
import de.bsnsoft.megarepo.rest.dto.firewall.FirewallPolicyXO;
import de.bsnsoft.megarepo.rest.dto.firewall.FirewallQuarantineDecisionXO;
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
import java.util.UUID;
import java.util.function.Predicate;

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
 * <h2>What Phase 2 adds to this class</h2>
 *
 * The engine gained three ways to answer that Phase 1 did not have, and each of
 * them has to reach the same verdict through a group as it does directly:
 *
 * <ul>
 *   <li>a <b>quarantining rule</b> holds the component instead of refusing it
 *       outright, and the queue entry an operator then works through has to name
 *       the member — a row naming the group would send them to a repository the
 *       component is not in. Releasing that entry has to serve the same download
 *       <em>through the group</em>, because the group is where the consumer is
 *       pointed and "released" that only takes effect on the direct URL is not
 *       released at all;</li>
 *   <li>an <b>exemption</b> is scoped to the repository that holds the component,
 *       so an exemption naming the member has to apply to a request addressed to
 *       the group. Scoping it to the group instead would be unapprovable: the
 *       group's membership can change under it;</li>
 *   <li>the <b>upload</b> path is enforced too, which is why the fixtures below
 *       publish before they arm the member rather than after. See
 *       {@link #publishInto}.</li>
 * </ul>
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
    private static final String EXEMPTED = "2.14.8";

    /**
     * Outside the advisory's range, so no advisory names it at all — which is
     * what {@code UNKNOWN_COMPONENT} is about, and therefore what gets it held
     * rather than refused.
     */
    private static final String HELD = "2.16.0";

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
        // Every download of the same component would otherwise leave at most one
        // row per day, and the exemption assertion is precisely about the
        // *second* verdict on a component the first one refused: with the
        // production window that row is suppressed as an exact repeat and the
        // exemption id has nowhere to appear.
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
    @Autowired private FirewallPolicyJpaRepository policies;
    @Autowired private FirewallPolicyRuleJpaRepository policyRules;
    @Autowired private RepositoryJpaRepository repositories;
    @Autowired private GroupMemberJpaRepository groupMembers;
    @Autowired private ComponentJpaRepository components;
    @Autowired private AssetJpaRepository assets;
    @Autowired private AdvisoryJpaRepository advisories;
    @Autowired private AdvisoryAffectedJpaRepository affected;
    @Autowired private PurlBuilder purlBuilder;

    private UUID memberId;
    private UUID spareId;
    private UUID groupId;

    @BeforeEach
    void freshInstallation() {
        violations.deleteAllInBatch();
        quarantineEntries.deleteAllInBatch();
        exemptions.deleteAllInBatch();
        firewallConfigs.deleteAllInBatch();
        // The policy V16 seeds stays; the ones a test method creates do not, or
        // the second method would inherit a rule set the first one wrote.
        policyRules.deleteAll(policyRules.findAll().stream()
                .filter(rule -> !isSeededDefault(rule.getPolicyId()))
                .toList());
        policies.deleteAll(policies.findAll().stream()
                .filter(policy -> !policy.isDefault())
                .toList());
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
        arm();
        publishInto(MEMBER, BLOCKED);
        armRepository(MEMBER, memberId);

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

        publishInto(MEMBER, AUDITED);

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
        arm();

        // The same coordinates in both members. The quarantined one is first.
        publishInto(MEMBER, SHADOWED);
        publishInto(SPARE, SHADOWED);
        armRepository(MEMBER, memberId);

        assertThat(downloadFrom(SPARE, SHADOWED).getStatusCode())
                .as("the spare has no firewall configuration, so directly it serves — which is "
                        + "what makes the next assertion mean something")
                .isEqualTo(HttpStatus.OK);

        assertThat(downloadFrom(GROUP, SHADOWED).getStatusCode())
                .as("falling through to the next member that happens to have the same file "
                        + "would make every group a documented bypass of its own members' policies")
                .isEqualTo(HttpStatus.FORBIDDEN);

        // Which member said no, and to whom. Without this the test would pass on
        // an implementation that refused the group download for some reason of
        // the group's own — the point is that the *first member's* policy did it.
        FirewallViolationEntity refusedBy = awaitViolation(
                SHADOWED, row -> Boolean.TRUE.equals(row.getRequestContext().get("blocked")));
        assertThat(refusedBy.getRepositoryId()).isEqualTo(memberId);
        assertThat(refusedBy.getRepositoryName()).isEqualTo(MEMBER);
        assertThat(refusedBy.getRequestContext()).containsEntry("viaRepository", GROUP);

        assertThat(downloadFrom(SPARE, SHADOWED).getStatusCode())
                .as("and the refusal is the group route's, not the artifact's: the spare that "
                        + "never asked to be enforced still hands out its own copy")
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("a component held in the member is held through the group, and releasing it frees the group route")
    void quarantineIsTheMembersAndReleaseReachesTheGroupRoute() {
        UUID policyId = givenPolicyThatHoldsUnknownComponents();
        assignPolicy(MEMBER, memberId, policyId);
        arm();
        publishInto(MEMBER, HELD);
        armRepository(MEMBER, memberId);

        // ── Held, not refused: the same 403, a different story ───────────────
        ResponseEntity<String> held = downloadFrom(GROUP, HELD);

        assertThat(held.getStatusCode())
                .as("a quarantining rule withholds the artifact just as firmly as a blocking "
                        + "one; the difference is that this one is expected to resolve itself")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(held.getHeaders().getFirst("X-MegaRepo-Firewall"))
                .as("the marker a proxy or CI plugin keys on stays 'blocked' whichever kind "
                        + "of refusal it is — the kind lives in the quarantine header")
                .isEqualTo("blocked");
        assertThat(held.getHeaders().getFirst("X-MegaRepo-Firewall-Quarantine"))
                .contains(FirewallQuarantineState.QUARANTINED.name())
                .contains(FirewallQuarantineReason.UNKNOWN_COMPONENT.name());
        assertThat(held.getBody())
                .as("the developer asked the group and has never heard of the member, so the "
                        + "body has to name both and say which of them decided")
                .contains("Requested  : " + GROUP + " (group)")
                .contains("Resolved by: " + MEMBER)
                .contains("log4j-core@" + HELD);

        // ── The queue entry is the member's, and an operator can act on it ───
        FirewallQuarantineEntity entry = onlyQuarantineEntry();
        assertThat(entry.getRepositoryId())
                .as("an entry naming the group would send the operator to a repository that "
                        + "holds no component and has no firewall configuration to change")
                .isEqualTo(memberId);
        assertThat(entry.getRepositoryName()).isEqualTo(MEMBER);
        assertThat(entry.getComponentKey()).endsWith("log4j-core@" + HELD);
        assertThat(entry.getState()).isEqualTo(FirewallQuarantineState.QUARANTINED);
        assertThat(entry.getReasonCode()).isEqualTo(FirewallQuarantineReason.UNKNOWN_COMPONENT);

        // ── Releasing it has to reach the route the consumer actually uses ───
        release(entry.getId());

        assertThat(downloadFrom(GROUP, HELD).getStatusCode())
                .as("the operator released it in the queue; a release that only takes effect "
                        + "on the member's own URL is not a release, because nobody downloads "
                        + "from there")
                .isEqualTo(HttpStatus.OK);
        assertThat(quarantineEntries.findById(entry.getId()).orElseThrow().getState())
                .as("and the entry keeps saying so rather than being re-derived per download")
                .isEqualTo(FirewallQuarantineState.RELEASED);
    }

    @Test
    @DisplayName("an approved exemption scoped to the member turns a group download into a served one")
    void anExemptionForTheMemberIsHonouredThroughTheGroup() {
        arm();
        publishInto(MEMBER, EXEMPTED);
        armRepository(MEMBER, memberId);

        assertThat(downloadFrom(GROUP, EXEMPTED).getStatusCode())
                .as("nothing covers it yet — this is the refusal the exemption is asked for")
                .isEqualTo(HttpStatus.FORBIDDEN);

        // Scoped to the member, which is the only repository that can be named:
        // the component lives there, and a group's membership can change under
        // an approval that was given for what it contained at the time.
        UUID exemptionId = approvedExemptionFor(
                componentKeyOf(memberId, EXEMPTED), memberId, FirewallRuleType.CVSS_THRESHOLD);

        assertThat(downloadFrom(GROUP, EXEMPTED).getStatusCode())
                .as("the exemption names the member and the request names the group, and the "
                        + "member is what resolved the artifact — so it applies")
                .isEqualTo(HttpStatus.OK);

        FirewallViolationEntity served = awaitViolation(
                EXEMPTED, row -> Boolean.TRUE.equals(row.getRequestContext().get("exempted")));
        assertThat(served.getRequestContext())
                .as("'a BLOCK rule matched and the artifact went out anyway' is not an audit "
                        + "trail; the row has to say which approval did it")
                .containsEntry("exemptionId", exemptionId.toString())
                .containsEntry("blocked", false)
                .containsEntry("rule", FirewallRuleType.CVSS_THRESHOLD.name())
                .containsEntry("viaRepository", GROUP);
        assertThat(served.getRepositoryId())
                .as("and it is filed against the member, like every other row here")
                .isEqualTo(memberId);
        assertThat(served.getRepositoryName()).isEqualTo(MEMBER);
    }

    @Test
    @DisplayName("what was already stored when the switch was flipped is still served through the group")
    void grandfatheringSurvivesTheGroupPath() {
        armRepository(MEMBER, memberId);
        publishInto(MEMBER, GRANDFATHERED);

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

    /**
     * A policy whose only rule holds anything no advisory source has heard of.
     *
     * <p>{@code UNKNOWN_COMPONENT} is the cheapest way to get a real
     * <em>quarantining</em> verdict out of the engine: it is the rule whose match
     * writes a queue entry rather than refusing outright, it needs no fact the
     * request path would have to wait for, and {@code includeHostedComponents}
     * opts the locally published component in — the rule ignores hosted artifacts
     * by default, because a colleague's package is not "unknown", it is ours.
     */
    private UUID givenPolicyThatHoldsUnknownComponents() {
        ResponseEntity<FirewallPolicyXO> created = admin().exchange(
                url("/api/v1/admin/firewall/policies"),
                HttpMethod.POST,
                json(new FirewallPolicyUpsertXO(
                        "group-e2e-hold-unknown",
                        "Holds components no advisory source names. End-to-end fixture.",
                        false,
                        List.of(new FirewallPolicyRuleXO(
                                null,
                                FirewallRuleType.UNKNOWN_COMPONENT,
                                FirewallAction.BLOCK,
                                Map.of("includeHostedComponents", true),
                                true,
                                false)),
                        null)),
                FirewallPolicyXO.class);
        assertThat(created.getStatusCode().is2xxSuccessful())
                .as("creating the fixture policy failed")
                .isTrue();
        assertThat(created.getBody()).isNotNull();
        return created.getBody().id();
    }

    /**
     * Assigns a policy to a repository, before it is armed.
     *
     * <p>Order matters: while the repository is not enforcing, this call needs no
     * confirmation phrase. Assigning a policy to an already-armed repository is
     * the call that can break somebody's build in the next second, and the API
     * demands the phrase for it — which is a different test's subject.
     */
    private void assignPolicy(String name, UUID repositoryId, UUID policyId) {
        ResponseEntity<FirewallRepositoryStateXO> response = admin().exchange(
                url("/api/v1/admin/firewall/repositories/" + repositoryId + "/policy"),
                HttpMethod.PUT,
                json(new FirewallRepositoryPolicyUpdateXO(policyId, null, null)),
                FirewallRepositoryStateXO.class);
        assertThat(response.getStatusCode())
                .as("assigning the policy to %s failed", name)
                .isEqualTo(HttpStatus.OK);
    }

    /** Releases a held component the way an operator does, from the queue. */
    private void release(UUID quarantineId) {
        ResponseEntity<String> released = admin().exchange(
                url("/api/v1/admin/firewall/quarantine/" + quarantineId + "/release"),
                HttpMethod.POST,
                json(new FirewallQuarantineDecisionXO(
                        "Reviewed for the end-to-end test; the component is ours.")),
                String.class);
        assertThat(released.getStatusCode())
                .as("releasing %s failed: %s", quarantineId, released.getBody())
                .isEqualTo(HttpStatus.OK);
    }

    /**
     * Files a request for an exemption and approves it, both over HTTP.
     *
     * <p>Two calls rather than one on purpose: an exemption that took effect on
     * submission would not be an exemption workflow but the absence of one, and
     * the test should go through the same two steps a developer and an approver
     * do.
     *
     * @return the approved exemption's id, which is what the violation row has to
     *     name
     */
    private UUID approvedExemptionFor(String componentKey, UUID repositoryId, FirewallRuleType rule) {
        ResponseEntity<FirewallExemptionXO> requested = admin().exchange(
                url(FirewallApiPaths.EXEMPTIONS),
                HttpMethod.POST,
                json(new FirewallExemptionRequestXO(
                        componentKey,
                        FirewallExemptionScope.VERSION,
                        repositoryId,
                        rule,
                        List.of(ADVISORY_ID),
                        null,
                        "This build needs it while the upgrade is prepared.")),
                FirewallExemptionXO.class);
        assertThat(requested.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(requested.getBody()).isNotNull();

        ResponseEntity<FirewallExemptionXO> approved = admin().exchange(
                url(FirewallApiPaths.EXEMPTIONS + "/" + requested.getBody().id() + "/approve"),
                HttpMethod.POST,
                json(new FirewallExemptionDecisionXO(null, "Approved for the end-to-end test.")),
                FirewallExemptionXO.class);
        assertThat(approved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(approved.getBody()).isNotNull();
        assertThat(approved.getBody().state()).isEqualTo(FirewallExemptionState.APPROVED);
        return approved.getBody().id();
    }

    // ── The repository API, over HTTP ────────────────────────────────────────

    /**
     * Publishes the artifact, which is what creates its component and asset rows.
     *
     * <p>Every caller publishes <em>before</em> the member is moved into
     * {@code QUARANTINE} and <em>after</em> the master switch is armed, and both
     * halves of that are load-bearing. The upload path is enforced as of Phase 2,
     * so publishing a component the policy denies into an armed member is itself
     * refused — correctly, and it would leave nothing here to download. Arming the
     * master switch first is what keeps the artifact out of the grandfathering
     * rule: the watermark is stamped when the switch goes on, so an asset written
     * after it is not "already in the repository" and the download-side
     * assertions still mean something.
     */
    private void publishInto(String repository, String version) {
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

    /** Whether this policy is the one V16 seeds, which the per-method purge keeps. */
    private boolean isSeededDefault(UUID policyId) {
        return policyId != null
                && policies.findById(policyId).map(FirewallPolicyEntity::isDefault).orElse(false);
    }

    /**
     * The single quarantine entry this instance is holding.
     *
     * <p>Read straight from the table rather than through the queue API, because
     * the assertion is about the column an operator's filter and every join key
     * off: {@code repository_id}. Not polled — the entry is written while the
     * verdict is being reached, so by the time the 403 is on the wire it exists.
     */
    private FirewallQuarantineEntity onlyQuarantineEntry() {
        List<FirewallQuarantineEntity> all = quarantineEntries.findAll();
        assertThat(all)
                .as("exactly one component should be held at this point")
                .hasSize(1);
        return all.get(0);
    }

    /**
     * The component key an exemption has to name, derived the same way the engine
     * derives it.
     *
     * <p>Spelling the purl out as a literal here would let the test and the engine
     * disagree about canonical form without anything failing until an exemption
     * silently stopped covering anything.
     */
    private String componentKeyOf(UUID repositoryId, String version) {
        ComponentEntity component = components.findAll().stream()
                .filter(row -> repositoryId.equals(row.getRepositoryId()))
                .filter(row -> version.equals(row.getVersion()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "No component row for version " + version + " in " + repositoryId));
        return purlBuilder.identify(component, null).key();
    }

    /**
     * The enforcement row for this version that satisfies the given condition.
     *
     * <p>Predicate rather than "the first row": with the suppression window at
     * zero every verdict leaves a row, so a version that was refused and then
     * served has two, and an assertion about the second one must not be allowed
     * to settle for the first.
     */
    private FirewallViolationEntity awaitViolation(
            String version, Predicate<FirewallViolationEntity> condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            List<FirewallViolationEntity> rows =
                    rowsFor(version, "enforcement").stream().filter(condition).toList();
            if (!rows.isEmpty()) {
                return rows.get(0);
            }
            sleep();
        }
        throw new AssertionError("No matching enforcement row for version " + version
                + "; rows: " + violations.findAll().stream()
                        .map(row -> row.getPurl() + " " + row.getRequestContext())
                        .toList());
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
