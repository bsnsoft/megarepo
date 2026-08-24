package de.bsnsoft.megarepo.database.migration;

import de.bsnsoft.megarepo.database.PostgresTestSupport;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Flyway migration tests for the repository firewall schema (V11 policies,
 * V12 advisories, V13 violations, V16 enforcement switch and default policy,
 * V17 quarantine/exemptions/component facts, V18 whitelist migration, V19 the
 * Phase 2 scheduled tasks).
 *
 * <p>Two paths are covered, because both happen in the field: a brand new
 * installation, and an existing installation that already runs the V8 NVD
 * firewall and must upgrade without losing anything.
 */
class FirewallMigrationTest {

    private static final List<String> FIREWALL_TABLES = List.of(
            "firewall_policy",
            "firewall_policy_rule",
            "firewall_repository_config",
            "firewall_enforcement_settings",
            "advisory",
            "advisory_affected",
            "advisory_sync_state",
            "firewall_violation",
            "firewall_quarantine",
            "firewall_exemption",
            "firewall_component_facts");

    /**
     * Firewall tables that carry no seeded data. {@code firewall_policy} and
     * {@code firewall_policy_rule} are missing on purpose: V16 seeds the default
     * policy, because a repository put into QUARANTINE with no policy assigned
     * has to resolve to something. {@code firewall_enforcement_settings} is
     * missing because its single row is the master switch itself — seeded off.
     */
    private static final List<String> UNSEEDED_FIREWALL_TABLES = List.of(
            "firewall_repository_config",
            "advisory",
            "advisory_affected",
            "advisory_sync_state",
            "firewall_violation",
            // V17's three tables start empty on every path. firewall_exemption is
            // the exception on an upgrade from V8, where V18 fills it from the
            // whitelist — see migratesOverExistingV8Installation, which asserts
            // that separately.
            "firewall_quarantine",
            "firewall_component_facts");

    /** V8 tables that must survive the upgrade untouched. */
    private static final List<String> V8_TABLES = List.of(
            "nvd_firewall_settings",
            "nvd_firewall_whitelist",
            "nvd_firewall_blocks",
            "nvd_sync_state",
            "cve_entries",
            "cve_affected_products");

    private static Flyway flywayFor(String jdbcUrl) {
        return Flyway.configure()
                .dataSource(jdbcUrl, PostgresTestSupport.USERNAME, PostgresTestSupport.PASSWORD)
                .locations("classpath:db/migration")
                .placeholderReplacement(false)
                .load();
    }

    @Test
    @DisplayName("migrates cleanly on an empty database and validates")
    void migratesOnEmptyDatabase() throws SQLException {
        String jdbcUrl = PostgresTestSupport.freshDatabase("fw_empty");
        Flyway flyway = flywayFor(jdbcUrl);

        MigrateResult result = flyway.migrate();

        assertThat(result.success).isTrue();
        assertThat(appliedVersions(flyway)).contains("11", "12", "13", "16", "17", "18", "19");
        // Throws if a checksum or ordering is off.
        flyway.validate();

        try (Connection connection = PostgresTestSupport.connect(jdbcUrl)) {
            for (String table : FIREWALL_TABLES) {
                assertThat(tableExists(connection, table))
                        .as("table %s created", table)
                        .isTrue();
            }
            for (String table : V8_TABLES) {
                assertThat(tableExists(connection, table))
                        .as("V8 table %s still present — nothing is dropped", table)
                        .isTrue();
            }
        }
    }

    @Test
    @DisplayName("upgrades a V8 installation without losing NVD firewall data")
    void migratesOverExistingV8Installation() throws SQLException {
        String jdbcUrl = PostgresTestSupport.freshDatabase("fw_from_v8");

        // 1. Bring the database to the state an existing installation is in.
        Flyway toV8 = Flyway.configure()
                .dataSource(jdbcUrl, PostgresTestSupport.USERNAME, PostgresTestSupport.PASSWORD)
                .locations("classpath:db/migration")
                .placeholderReplacement(false)
                .target(MigrationVersion.fromVersion("8"))
                .load();
        assertThat(toV8.migrate().success).isTrue();

        // 2. Give it real data, the way a running instance would have.
        try (Connection connection = PostgresTestSupport.connect(jdbcUrl);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "UPDATE nvd_firewall_settings SET enabled = true, cvss_threshold = 8.5 WHERE id = 1");
            statement.executeUpdate("""
                    INSERT INTO nvd_firewall_whitelist (entry_type, value, reason, added_by)
                    VALUES ('COMPONENT', 'maven2:com.acme:util:1.0.0', 'false positive', 'admin')
                    """);
            // Version-less: the V8 matcher's prefix rule covered every version.
            statement.executeUpdate("""
                    INSERT INTO nvd_firewall_whitelist (entry_type, value, added_by)
                    VALUES ('COMPONENT', 'npm::left-pad', 'ci')
                    """);
            // Not component-scoped, and deliberately not migrated.
            statement.executeUpdate("""
                    INSERT INTO nvd_firewall_whitelist (entry_type, value, reason, added_by)
                    VALUES ('CVE', 'CVE-2021-44228', 'accepted risk', 'admin')
                    """);
            statement.executeUpdate("""
                    INSERT INTO cve_entries (cve_id, published, last_modified, cvss_score, severity)
                    VALUES ('CVE-2021-44228', NOW(), NOW(), 10.0, 'CRITICAL')
                    """);
            statement.executeUpdate("""
                    INSERT INTO nvd_firewall_blocks (repository, path, component_key, max_cvss_score)
                    VALUES ('maven-central', 'org/apache/logging/log4j/log4j-core/2.14.1/log4j-core-2.14.1.jar',
                            'org.apache.logging.log4j:log4j-core:2.14.1', 10.0)
                    """);
        }

        // 3. Upgrade to head.
        Flyway flyway = flywayFor(jdbcUrl);
        MigrateResult result = flyway.migrate();

        assertThat(result.success).isTrue();
        assertThat(result.migrationsExecuted).isGreaterThanOrEqualTo(3);
        assertThat(appliedVersions(flyway)).contains("11", "12", "13", "16", "17", "18", "19");

        try (Connection connection = PostgresTestSupport.connect(jdbcUrl)) {
            // 4. Nothing from V8 was dropped or emptied.
            assertThat(scalarLong(connection, "SELECT COUNT(*) FROM nvd_firewall_whitelist")).isEqualTo(3);
            assertThat(scalarLong(connection, "SELECT COUNT(*) FROM nvd_firewall_blocks")).isEqualTo(1);
            assertThat(scalarLong(connection, "SELECT COUNT(*) FROM cve_entries")).isEqualTo(1);
            assertThat(scalarDouble(connection, "SELECT cvss_threshold FROM nvd_firewall_settings WHERE id = 1"))
                    .isEqualTo(8.5);
            assertThat(scalarBoolean(connection, "SELECT enabled FROM nvd_firewall_settings WHERE id = 1"))
                    .isTrue();

            // 5. The new tables are there, and none of them was given data
            //    derived from the V8 firewall — that migration is still deferred.
            for (String table : FIREWALL_TABLES) {
                assertThat(tableExists(connection, table)).as("table %s created", table).isTrue();
            }
            for (String table : UNSEEDED_FIREWALL_TABLES) {
                assertThat(scalarLong(connection, "SELECT COUNT(*) FROM " + table))
                        .as("%s starts empty; the V8 data migration is still deferred", table)
                        .isZero();
            }

            // 5b. …except firewall_exemption, which V18 fills from the two
            //     component-scoped whitelist rows. The CVE row is not a
            //     component and is deliberately left behind.
            assertThat(scalarLong(connection, "SELECT COUNT(*) FROM firewall_exemption"))
                    .as("component whitelist entries become approved exemptions")
                    .isEqualTo(2);

            // 6. The one thing an upgrade must never do: start blocking.
            assertThat(scalarBoolean(
                    connection, "SELECT enabled FROM firewall_enforcement_settings WHERE id = 1"))
                    .as("upgrading must not switch enforcement on")
                    .isFalse();
            assertThat(scalarLong(
                    connection, "SELECT COUNT(*) FROM firewall_repository_config WHERE mode = 'QUARANTINE'"))
                    .as("upgrading must not put any repository into QUARANTINE")
                    .isZero();
        }
    }

    @Test
    @DisplayName("V16 seeds the master switch off and a default policy with the two implemented rules")
    void enforcementSeedIsInertUntilSwitchedOn() throws SQLException {
        String jdbcUrl = PostgresTestSupport.freshDatabase("fw_enforcement");
        Flyway flyway = flywayFor(jdbcUrl);
        assertThat(flyway.migrate().success).isTrue();
        assertThat(appliedVersions(flyway)).contains("16");

        try (Connection connection = PostgresTestSupport.connect(jdbcUrl)) {
            assertThat(scalarLong(connection, "SELECT COUNT(*) FROM firewall_enforcement_settings"))
                    .isEqualTo(1);
            assertThat(scalarBoolean(
                    connection, "SELECT enabled FROM firewall_enforcement_settings WHERE id = 1"))
                    .isFalse();
            assertThat(scalarBoolean(
                    connection, "SELECT configured FROM firewall_enforcement_settings WHERE id = 1"))
                    .as("nobody has decided yet, so the deployment-side property still applies")
                    .isFalse();
            assertThat(scalarLong(connection,
                    "SELECT COUNT(*) FROM firewall_enforcement_settings WHERE enforcing_since IS NULL"))
                    .as("the grandfathering watermark is stamped on first enable, not by the migration")
                    .isEqualTo(1);

            assertThat(scalarLong(connection, "SELECT COUNT(*) FROM firewall_policy WHERE is_default"))
                    .isEqualTo(1);
            assertThat(scalarLong(connection, """
                    SELECT COUNT(*) FROM firewall_policy_rule r
                    JOIN firewall_policy p ON p.id = r.policy_id
                    WHERE p.is_default AND r.rule_type IN ('CVSS_THRESHOLD', 'KNOWN_MALICIOUS')
                      AND r.action = 'BLOCK' AND r.enabled
                    """)).isEqualTo(2);
            assertThat(scalarLong(connection, """
                    SELECT COUNT(*) FROM firewall_policy_rule r
                    JOIN firewall_policy p ON p.id = r.policy_id
                    WHERE p.is_default AND r.rule_type NOT IN ('CVSS_THRESHOLD', 'KNOWN_MALICIOUS')
                    """))
                    .as("seeding a rule type the engine does not implement would be a rule nobody enforces")
                    .isZero();
        }
    }

    @Test
    @DisplayName("V16 does not add a second default policy to an installation that already has one")
    void defaultPolicySeedIsConditional() throws SQLException {
        String jdbcUrl = PostgresTestSupport.freshDatabase("fw_own_policy");

        Flyway toV15 = Flyway.configure()
                .dataSource(jdbcUrl, PostgresTestSupport.USERNAME, PostgresTestSupport.PASSWORD)
                .locations("classpath:db/migration")
                .placeholderReplacement(false)
                .target(MigrationVersion.fromVersion("15"))
                .load();
        assertThat(toV15.migrate().success).isTrue();

        try (Connection connection = PostgresTestSupport.connect(jdbcUrl);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "INSERT INTO firewall_policy (name, is_default) VALUES ('House rules', true)");
        }

        assertThat(flywayFor(jdbcUrl).migrate().success).isTrue();

        try (Connection connection = PostgresTestSupport.connect(jdbcUrl)) {
            assertThat(scalarLong(connection, "SELECT COUNT(*) FROM firewall_policy WHERE is_default"))
                    .isEqualTo(1);
            assertThat(scalarLong(
                    connection, "SELECT COUNT(*) FROM firewall_policy WHERE name = 'House rules'"))
                    .as("the operator's own default policy is left alone")
                    .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("V17 constrains the closed Phase 2 enums and leaves the open ones open")
    void phase2CheckConstraints() throws SQLException {
        String jdbcUrl = PostgresTestSupport.freshDatabase("fw_phase2_checks");
        assertThat(flywayFor(jdbcUrl).migrate().success).isTrue();

        try (Connection connection = PostgresTestSupport.connect(jdbcUrl);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO repositories (id, name, format, type, blob_store_name)
                    VALUES ('33333333-3333-3333-3333-333333333333', 'maven-hosted', 'maven2', 'HOSTED', 'default')
                    """);

            // Quarantine state is a closed set: a fourth state is a behaviour
            // change, not a new rule. decided_at and resolution are supplied so
            // that the row satisfies firewall_quarantine_decided_is_complete and
            // the state check is the only thing left to fail.
            assertThatThrownBy(() -> statement.executeUpdate("""
                    INSERT INTO firewall_quarantine
                        (repository_id, repository_name, component_key, state, reason_code,
                         resolution, decided_at, decided_by)
                    VALUES ('33333333-3333-3333-3333-333333333333', 'maven-hosted',
                            'pkg:maven/com.acme/util@1.0', 'PONDERED', 'MIN_AGE_NOT_MET',
                            'MANUAL_RELEASE', NOW(), 'admin')
                    """))
                    .hasMessageContaining("firewall_quarantine_state_check");

            // reason_code is deliberately unconstrained: a new rule type must be
            // able to name its own quarantine reason without a migration.
            assertThat(statement.executeUpdate("""
                    INSERT INTO firewall_quarantine
                        (repository_id, repository_name, component_key, state, reason_code)
                    VALUES ('33333333-3333-3333-3333-333333333333', 'maven-hosted',
                            'pkg:maven/com.acme/util@1.0', 'QUARANTINED', 'SOME_FUTURE_REASON')
                    """)).isEqualTo(1);

            // One entry per component per repository, not one per path.
            assertThatThrownBy(() -> statement.executeUpdate("""
                    INSERT INTO firewall_quarantine
                        (repository_id, repository_name, component_key, state, reason_code)
                    VALUES ('33333333-3333-3333-3333-333333333333', 'maven-hosted',
                            'pkg:maven/com.acme/util@1.0', 'QUARANTINED', 'UNKNOWN_COMPONENT')
                    """))
                    .hasMessageContaining("firewall_quarantine_unique_component");

            // A decided entry has to say who, when and how.
            assertThatThrownBy(() -> statement.executeUpdate("""
                    INSERT INTO firewall_quarantine
                        (repository_id, repository_name, component_key, state, reason_code)
                    VALUES ('33333333-3333-3333-3333-333333333333', 'maven-hosted',
                            'pkg:maven/com.acme/other@1.0', 'RELEASED', 'MIN_AGE_NOT_MET')
                    """))
                    .hasMessageContaining("firewall_quarantine_decided_is_complete");

            // An approved exemption without an approver is one nobody signed.
            assertThatThrownBy(() -> statement.executeUpdate("""
                    INSERT INTO firewall_exemption
                        (component_key, state, justification, requested_by)
                    VALUES ('pkg:npm/left-pad@1.3.0', 'APPROVED', 'needed for the build', 'dev')
                    """))
                    .hasMessageContaining("firewall_exemption_approved_has_approver");

            assertThatThrownBy(() -> statement.executeUpdate("""
                    INSERT INTO firewall_exemption
                        (component_key, scope_type, state, justification, requested_by)
                    VALUES ('pkg:npm/left-pad@1.3.0', 'EVERYTHING', 'REQUESTED', 'why not', 'dev')
                    """))
                    .hasMessageContaining("firewall_exemption_scope_check");

            // Declared metadata only — a licence read from file contents has no
            // value to record here, and the constraint is what says so.
            assertThatThrownBy(() -> statement.executeUpdate("""
                    INSERT INTO firewall_component_facts (purl, purl_type, state, license_source)
                    VALUES ('pkg:maven/com.acme/util@1.0', 'maven', 'RESOLVED', 'FILE_SCAN')
                    """))
                    .hasMessageContaining("firewall_component_facts_license_source_check");
        }
    }

    @Test
    @DisplayName("V18 carries V8 whitelist rows over as non-expiring approved exemptions")
    void whitelistBecomesExemptions() throws SQLException {
        String jdbcUrl = PostgresTestSupport.freshDatabase("fw_whitelist");

        Flyway toV8 = Flyway.configure()
                .dataSource(jdbcUrl, PostgresTestSupport.USERNAME, PostgresTestSupport.PASSWORD)
                .locations("classpath:db/migration")
                .placeholderReplacement(false)
                .target(MigrationVersion.fromVersion("8"))
                .load();
        assertThat(toV8.migrate().success).isTrue();

        try (Connection connection = PostgresTestSupport.connect(jdbcUrl);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO nvd_firewall_whitelist (entry_type, value, reason, added_by)
                    VALUES ('COMPONENT', 'maven2:org.apache.logging.log4j:log4j-core:2.14.1',
                            'mitigated by configuration', 'alice')
                    """);
            statement.executeUpdate("""
                    INSERT INTO nvd_firewall_whitelist (entry_type, value, added_by)
                    VALUES ('COMPONENT', 'maven2:com.acme:util', 'bob')
                    """);
            statement.executeUpdate("""
                    INSERT INTO nvd_firewall_whitelist (entry_type, value, added_by)
                    VALUES ('CVE', 'CVE-2021-44228', 'carol')
                    """);
        }

        assertThat(flywayFor(jdbcUrl).migrate().success).isTrue();

        try (Connection connection = PostgresTestSupport.connect(jdbcUrl)) {
            // Only the component-scoped rows. A CVE entry says "ignore this
            // advisory everywhere", which has no component to scope an exemption
            // to, so it is left where it is rather than silently widened.
            assertThat(scalarLong(connection, "SELECT COUNT(*) FROM firewall_exemption")).isEqualTo(2);
            assertThat(scalarLong(connection,
                    "SELECT COUNT(*) FROM firewall_exemption WHERE key_kind = 'LEGACY_COORDINATE'"))
                    .as("the V8 value is a coordinate, not a purl, and is kept verbatim")
                    .isEqualTo(2);

            // Approved and non-expiring: an upgrade must not start blocking what
            // the operator had already allowed, and must not invent an end date.
            assertThat(scalarLong(connection,
                    "SELECT COUNT(*) FROM firewall_exemption WHERE state = 'APPROVED' AND expires_at IS NULL"))
                    .isEqualTo(2);
            assertThat(scalarLong(connection,
                    "SELECT COUNT(*) FROM firewall_exemption WHERE rule_type IS NOT NULL "
                            + "OR repository_id IS NOT NULL"))
                    .as("the V8 whitelist was global and short-circuited every check")
                    .isZero();

            // Scope is read off the V8 matcher's behaviour: three colons matched
            // one version, two colons matched every version.
            assertThat(scalarString(connection, """
                    SELECT scope_type FROM firewall_exemption
                    WHERE component_key = 'maven2:org.apache.logging.log4j:log4j-core:2.14.1'
                    """)).isEqualTo("VERSION");
            assertThat(scalarString(connection, """
                    SELECT scope_type FROM firewall_exemption
                    WHERE component_key = 'maven2:com.acme:util'
                    """)).isEqualTo("COMPONENT");

            // Who and why survive the move; a row with no stated reason gets one
            // that says there was none, because justification is NOT NULL.
            assertThat(scalarString(connection, """
                    SELECT approved_by FROM firewall_exemption
                    WHERE component_key = 'maven2:org.apache.logging.log4j:log4j-core:2.14.1'
                    """)).isEqualTo("alice");
            assertThat(scalarString(connection, """
                    SELECT justification FROM firewall_exemption
                    WHERE component_key = 'maven2:com.acme:util'
                    """)).contains("no reason was recorded");

            // Nothing was taken away from V8 either.
            assertThat(scalarLong(connection, "SELECT COUNT(*) FROM nvd_firewall_whitelist")).isEqualTo(3);
        }
    }

    @Test
    @DisplayName("V19 seeds the Phase 2 tasks with a due next_run")
    void phase2TasksAreScheduled() throws SQLException {
        String jdbcUrl = PostgresTestSupport.freshDatabase("fw_tasks");
        assertThat(flywayFor(jdbcUrl).migrate().success).isTrue();

        try (Connection connection = PostgresTestSupport.connect(jdbcUrl)) {
            // A seeded task row with next_run NULL is inert until somebody
            // triggers it by hand — V15 established not relying on that.
            assertThat(scalarLong(connection, """
                    SELECT COUNT(*) FROM scheduled_tasks
                    WHERE type IN ('security.firewall.quarantine.reevaluate',
                                   'security.firewall.exemption.expiry',
                                   'security.firewall.facts.resolve')
                      AND next_run IS NOT NULL
                      AND cron_expression IS NOT NULL
                      AND enabled
                    """)).isEqualTo(3);
        }
    }

    @Test
    @DisplayName("advisory lookup index covers (purl_type, purl_namespace, purl_name)")
    void advisoryPurlIndexExists() throws SQLException {
        String jdbcUrl = PostgresTestSupport.freshDatabase("fw_index");
        assertThat(flywayFor(jdbcUrl).migrate().success).isTrue();

        try (Connection connection = PostgresTestSupport.connect(jdbcUrl)) {
            String definition = indexDefinition(connection, "idx_advisory_affected_purl");
            assertThat(definition)
                    .contains("purl_type")
                    .contains("purl_namespace")
                    .contains("purl_name");

            // V14: NVD's CPE-derived rows carry no usable namespace, so the
            // lookup matches them on (purl_type, purl_name) and skips the middle
            // column of the index above — which needs an index of its own.
            assertThat(indexDefinition(connection, "idx_advisory_affected_purl_name"))
                    .contains("purl_type")
                    .contains("purl_name")
                    .doesNotContain("purl_namespace");

            assertThat(indexDefinition(connection, "idx_firewall_policy_single_default"))
                    .as("single default policy is enforced by a partial unique index")
                    .contains("UNIQUE")
                    .contains("is_default");
        }
    }

    @Test
    @DisplayName("closed enum columns are constrained, rule_type stays open")
    void checkConstraintsMatchTheEnumDecisions() throws SQLException {
        String jdbcUrl = PostgresTestSupport.freshDatabase("fw_checks");
        assertThat(flywayFor(jdbcUrl).migrate().success).isTrue();

        try (Connection connection = PostgresTestSupport.connect(jdbcUrl);
             Statement statement = connection.createStatement()) {
            // Not named "Default": V16 already seeded a policy under that name.
            statement.executeUpdate(
                    "INSERT INTO firewall_policy (id, name) VALUES ('11111111-1111-1111-1111-111111111111', 'Probe')");

            // action is a closed set.
            assertThatThrownBy(() -> statement.executeUpdate("""
                    INSERT INTO firewall_policy_rule (policy_id, rule_type, action)
                    VALUES ('11111111-1111-1111-1111-111111111111', 'CVSS_THRESHOLD', 'NONSENSE')
                    """))
                    .hasMessageContaining("firewall_policy_rule_action_check");

            // mode and fail_mode likewise.
            statement.executeUpdate("""
                    INSERT INTO repositories (id, name, format, type, blob_store_name)
                    VALUES ('22222222-2222-2222-2222-222222222222', 'maven-proxy', 'maven2', 'PROXY', 'default')
                    """);
            assertThatThrownBy(() -> statement.executeUpdate("""
                    INSERT INTO firewall_repository_config (repository_id, mode)
                    VALUES ('22222222-2222-2222-2222-222222222222', 'PARANOID')
                    """))
                    .hasMessageContaining("firewall_repository_config_mode_check");

            // rule_type is intentionally unconstrained: a new rule type must be a
            // code change, not a migration (design section 3).
            int inserted = statement.executeUpdate("""
                    INSERT INTO firewall_policy_rule (policy_id, rule_type, action)
                    VALUES ('11111111-1111-1111-1111-111111111111', 'SOME_FUTURE_RULE', 'WARN')
                    """);
            assertThat(inserted).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("only one policy can be the default")
    void singleDefaultPolicyIsEnforced() throws SQLException {
        String jdbcUrl = PostgresTestSupport.freshDatabase("fw_default");
        assertThat(flywayFor(jdbcUrl).migrate().success).isTrue();

        try (Connection connection = PostgresTestSupport.connect(jdbcUrl);
             Statement statement = connection.createStatement()) {
            // V16 seeded the one default policy there may be.
            assertThatThrownBy(() -> statement.executeUpdate(
                    "INSERT INTO firewall_policy (name, is_default) VALUES ('Second', true)"))
                    .hasMessageContaining("idx_firewall_policy_single_default");

            // Any number of non-default policies is fine.
            assertThat(statement.executeUpdate(
                    "INSERT INTO firewall_policy (name, is_default) VALUES ('Strict', false)")).isEqualTo(1);
            assertThat(statement.executeUpdate(
                    "INSERT INTO firewall_policy (name, is_default) VALUES ('Lenient', false)")).isEqualTo(1);
        }
    }

    private static List<String> appliedVersions(Flyway flyway) {
        List<String> versions = new ArrayList<>();
        for (MigrationInfo info : flyway.info().applied()) {
            if (info.getVersion() != null) {
                versions.add(info.getVersion().getVersion());
            }
        }
        return versions;
    }

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT to_regclass(?) IS NOT NULL")) {
            ps.setString(1, "public." + table);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }

    private static String indexDefinition(Connection connection, String indexName) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND indexname = ?")) {
            ps.setString(1, indexName);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("index %s exists", indexName).isTrue();
                return rs.getString(1);
            }
        }
    }

    private static long scalarLong(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static String scalarString(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            assertThat(rs.next()).as("query returned a row: %s", sql).isTrue();
            return rs.getString(1);
        }
    }

    private static double scalarDouble(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getDouble(1);
        }
    }

    private static boolean scalarBoolean(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getBoolean(1);
        }
    }
}
