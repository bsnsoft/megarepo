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
 * Flyway migration tests for the Phase 1 repository firewall schema
 * (V11 policies, V12 advisories, V13 violations).
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
            "advisory",
            "advisory_affected",
            "advisory_sync_state",
            "firewall_violation");

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
        assertThat(appliedVersions(flyway)).contains("11", "12", "13");
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
                    VALUES ('COMPONENT', 'com.acme:util:1.0.0', 'false positive', 'admin')
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
        assertThat(appliedVersions(flyway)).contains("11", "12", "13");

        try (Connection connection = PostgresTestSupport.connect(jdbcUrl)) {
            // 4. Nothing from V8 was dropped or emptied.
            assertThat(scalarLong(connection, "SELECT COUNT(*) FROM nvd_firewall_whitelist")).isEqualTo(1);
            assertThat(scalarLong(connection, "SELECT COUNT(*) FROM nvd_firewall_blocks")).isEqualTo(1);
            assertThat(scalarLong(connection, "SELECT COUNT(*) FROM cve_entries")).isEqualTo(1);
            assertThat(scalarDouble(connection, "SELECT cvss_threshold FROM nvd_firewall_settings WHERE id = 1"))
                    .isEqualTo(8.5);
            assertThat(scalarBoolean(connection, "SELECT enabled FROM nvd_firewall_settings WHERE id = 1"))
                    .isTrue();

            // 5. The new tables are there and empty — Phase 1 adds no data migration.
            for (String table : FIREWALL_TABLES) {
                assertThat(tableExists(connection, table)).as("table %s created", table).isTrue();
                assertThat(scalarLong(connection, "SELECT COUNT(*) FROM " + table))
                        .as("%s starts empty; the V8 data migration is Phase 2", table)
                        .isZero();
            }
        }
    }

    @Test
    @DisplayName("Phase 2 tables are deliberately absent")
    void quarantineAndExemptionTablesAreNotCreatedYet() throws SQLException {
        String jdbcUrl = PostgresTestSupport.freshDatabase("fw_scope");
        assertThat(flywayFor(jdbcUrl).migrate().success).isTrue();

        try (Connection connection = PostgresTestSupport.connect(jdbcUrl)) {
            assertThat(tableExists(connection, "firewall_quarantine"))
                    .as("firewall_quarantine belongs to Phase 2")
                    .isFalse();
            assertThat(tableExists(connection, "firewall_exemption"))
                    .as("firewall_exemption belongs to Phase 2")
                    .isFalse();
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
            statement.executeUpdate(
                    "INSERT INTO firewall_policy (id, name) VALUES ('11111111-1111-1111-1111-111111111111', 'Default')");

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
            statement.executeUpdate("INSERT INTO firewall_policy (name, is_default) VALUES ('Default', true)");
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
