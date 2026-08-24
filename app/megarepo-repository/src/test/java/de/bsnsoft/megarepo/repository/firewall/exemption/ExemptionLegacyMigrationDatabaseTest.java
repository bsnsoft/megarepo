package de.bsnsoft.megarepo.repository.firewall.exemption;

import com.github.packageurl.PackageURL;
import de.bsnsoft.megarepo.core.firewall.FirewallComponentKeyKind;
import de.bsnsoft.megarepo.core.firewall.FirewallExemptionScope;
import de.bsnsoft.megarepo.core.firewall.FirewallExemptionState;
import de.bsnsoft.megarepo.database.entity.FirewallExemptionEntity;
import de.bsnsoft.megarepo.repository.firewall.identity.ComponentIdentity;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole chain, once: a real V8 whitelist row, the real V18 migration, and
 * the real matcher.
 *
 * <p>The customer's requirement is that an operator's existing whitelist entry
 * goes on working the day after the upgrade, and that claim spans a SQL
 * migration and a Java comparison written a package apart. Testing them
 * separately proves each half against the other half's assumptions — which is
 * exactly how the two halves come to disagree. So this test performs the
 * upgrade: it stops Flyway at V17, writes the rows an existing installation
 * would have, lets V18 run, reads what V18 produced, and asks
 * {@link ExemptionKeyBuilder} whether a download of that component is covered.
 *
 * <p>No Spring and no JPA on purpose. The subject is the migration's output and
 * the matcher's answer; an entity mapping in between would only add a way for
 * the test to pass for the wrong reason.
 *
 * <p>Nothing here reaches the internet beyond pulling {@code postgres:16-alpine}.
 */
class ExemptionLegacyMigrationDatabaseTest {

    private static final String USERNAME = "megarepo";
    private static final String PASSWORD = "megarepo";

    /** V17 is the last migration before the whitelist carry-over. */
    private static final MigrationVersion BEFORE_CARRY_OVER = MigrationVersion.fromVersion("17");

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("megarepo")
                    .withUsername(USERNAME)
                    .withPassword(PASSWORD)
                    .withUrlParam("stringtype", "unspecified")
                    .waitingFor(Wait.forListeningPort());

    private static String jdbcUrl;

    @BeforeAll
    static void upgradeAnExistingInstallation() throws SQLException {
        POSTGRES.start();
        awaitJdbcReady();
        jdbcUrl = POSTGRES.getJdbcUrl();

        // 1. The state an installation running the V8 NVD firewall is in.
        flyway(BEFORE_CARRY_OVER).migrate();

        // 2. What its operator whitelisted. Three colons pinned one version, two
        //    matched every version — that is the V8 matcher's behaviour, and the
        //    scope V18 derives from it is what this test is here to check.
        try (Connection connection = connect()) {
            whitelist(connection, "COMPONENT", "maven2:com.acme:util:1.0.0", "audited for the 4.2 release");
            whitelist(connection, "COMPONENT", "maven2:com.acme:widget", "internal, never proxied");
            whitelist(connection, "COMPONENT", "npm:@acme:toolkit:2.1.0", "vendor confirmed the fix");
            whitelist(connection, "CVE", "CVE-2021-44228", "handled by a config change");
        }

        // 3. The upgrade.
        flyway(MigrationVersion.LATEST).migrate();
    }

    @Test
    @DisplayName("a pinned legacy row still covers exactly the version it named")
    void pinnedVersion() throws Exception {
        FirewallExemptionEntity row = exemption("maven2:com.acme:util:1.0.0");

        assertThat(row.getKeyKind()).isEqualTo(FirewallComponentKeyKind.LEGACY_COORDINATE);
        assertThat(row.getScopeType())
                .as("three colons pinned one version under V8")
                .isEqualTo(FirewallExemptionScope.VERSION);
        assertThat(row.getState()).isEqualTo(FirewallExemptionState.APPROVED);
        assertThat(row.getExpiresAt())
                .as("an invented expiry would turn a silent upgrade into a dated build break")
                .isNull();

        assertThat(covers(row, maven("com.acme", "util", "1.0.0"))).isTrue();
        assertThat(covers(row, maven("com.acme", "util", "1.0.1")))
                .as("the neighbouring version was never on the V8 list")
                .isFalse();
        assertThat(covers(row, maven("org.other", "util", "1.0.0"))).isFalse();
    }

    @Test
    @DisplayName("a version-less legacy row still covers every version — the V8 prefix rule")
    void everyVersion() throws Exception {
        FirewallExemptionEntity row = exemption("maven2:com.acme:widget");

        assertThat(row.getScopeType())
                .as("two colons matched every version under V8")
                .isEqualTo(FirewallExemptionScope.COMPONENT);

        assertThat(covers(row, maven("com.acme", "widget", "1.0.0"))).isTrue();
        assertThat(covers(row, maven("com.acme", "widget", "9.9.9-SNAPSHOT"))).isTrue();
        assertThat(covers(row, maven("com.acme", "widget-extra", "1.0.0")))
                .as("a longer name is a different component, not a wider match")
                .isFalse();
    }

    @Test
    @DisplayName("an npm scope survives the round trip through the purl")
    void scopedNpm() throws Exception {
        FirewallExemptionEntity row = exemption("npm:@acme:toolkit:2.1.0");

        ComponentIdentity toolkit = new ComponentIdentity.Purl(
                new PackageURL("npm", "@acme", "toolkit", "2.1.0", null, null));
        assertThat(covers(row, toolkit)).isTrue();

        ComponentIdentity other = new ComponentIdentity.Purl(
                new PackageURL("npm", "@acme", "toolkit", "2.2.0", null, null));
        assertThat(covers(row, other)).isFalse();
    }

    @Test
    @DisplayName("the CVE row is not migrated — there is no component to scope it to")
    void cveRowsStayBehind() throws SQLException {
        assertThat(componentKeys())
                .as("'ignore this advisory everywhere' is not an exemption")
                .doesNotContain("CVE-2021-44228");

        try (Connection connection = connect();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(
                        "SELECT COUNT(*) FROM nvd_firewall_whitelist WHERE entry_type = 'CVE'")) {
            result.next();
            assertThat(result.getLong(1))
                    .as("the row stays where it is; the V8 firewall keeps enforcing it")
                    .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("the operator's reason and author are carried over, not replaced")
    void provenanceSurvives() throws SQLException {
        FirewallExemptionEntity row = exemption("maven2:com.acme:util:1.0.0");

        assertThat(row.getJustification()).isEqualTo("audited for the 4.2 release");
        assertThat(row.getRequestedBy()).isEqualTo("upgrade-test");
        assertThat(row.getApprovedBy()).isEqualTo("upgrade-test");
    }

    @Test
    @DisplayName("only the component rows became exemptions")
    void exactlyThreeRows() throws SQLException {
        assertThat(componentKeys()).hasSize(3);
    }

    // ── Fixtures ────────────────────────────────────────────────────────

    private static boolean covers(FirewallExemptionEntity row, ComponentIdentity identity) {
        return ExemptionKeyBuilder.candidates(identity, true).covers(row);
    }

    private static ComponentIdentity maven(String group, String artifact, String version)
            throws Exception {
        return new ComponentIdentity.Purl(new PackageURL("maven", group, artifact, version, null, null));
    }

    private static Flyway flyway(MigrationVersion target) {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), USERNAME, PASSWORD)
                .locations("classpath:db/migration")
                .placeholderReplacement(false)
                .target(target)
                .load();
    }

    private static void whitelist(Connection connection, String type, String value, String reason)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO nvd_firewall_whitelist (entry_type, value, reason, added_by)
                VALUES (?, ?, ?, 'upgrade-test')
                """)) {
            statement.setString(1, type);
            statement.setString(2, value);
            statement.setString(3, reason);
            statement.executeUpdate();
        }
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, USERNAME, PASSWORD);
    }

    private static List<String> componentKeys() throws SQLException {
        List<String> keys = new ArrayList<>();
        try (Connection connection = connect();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT component_key FROM firewall_exemption")) {
            while (result.next()) {
                keys.add(result.getString(1));
            }
        }
        return keys;
    }

    /**
     * The migrated row, read straight out of the table into the entity shape the
     * matcher compares against. Deliberately hand-mapped: the point is what V18
     * wrote, not what an ORM would make of it.
     */
    private static FirewallExemptionEntity exemption(String componentKey) throws SQLException {
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT component_key, key_kind, scope_type, state, expires_at,
                               justification, requested_by, approved_by
                        FROM firewall_exemption WHERE component_key = ?
                        """)) {
            statement.setString(1, componentKey);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).as("V18 wrote a row for %s", componentKey).isTrue();
                FirewallExemptionEntity entity = new FirewallExemptionEntity();
                entity.setComponentKey(result.getString("component_key"));
                entity.setKeyKind(FirewallComponentKeyKind.valueOf(result.getString("key_kind")));
                entity.setScopeType(FirewallExemptionScope.valueOf(result.getString("scope_type")));
                entity.setState(FirewallExemptionState.valueOf(result.getString("state")));
                entity.setExpiresAt(
                        result.getTimestamp("expires_at") == null
                                ? null
                                : result.getTimestamp("expires_at").toInstant());
                entity.setJustification(result.getString("justification"));
                entity.setRequestedBy(result.getString("requested_by"));
                entity.setApprovedBy(result.getString("approved_by"));
                return entity;
            }
        }
    }

    private static void awaitJdbcReady() {
        long deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
        SQLException last = null;
        while (System.nanoTime() < deadline) {
            try (Connection ignored =
                    DriverManager.getConnection(POSTGRES.getJdbcUrl(), USERNAME, PASSWORD)) {
                return;
            } catch (SQLException e) {
                last = e;
                try {
                    Thread.sleep(250);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while waiting for PostgreSQL", interrupted);
                }
            }
        }
        throw new IllegalStateException("PostgreSQL container never became reachable", last);
    }
}
