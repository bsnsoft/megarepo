package de.bsnsoft.megarepo.database;

import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;

/**
 * One PostgreSQL container for the whole test JVM, plus helpers to carve an
 * isolated database out of it per test.
 *
 * <p>Deliberately not {@code @Testcontainers}/{@code @Container}: that starts and
 * stops a container per test class, and these tests only need one. Ryuk reaps it
 * when the JVM exits.
 *
 * <p>The image matches {@code app/docker-compose.yml} (postgres:16-alpine) so the
 * tests exercise the same server version developers and CI run against. Nothing
 * here reaches the internet beyond pulling that image.
 */
public final class PostgresTestSupport {

    public static final String USERNAME = "megarepo";
    public static final String PASSWORD = "megarepo";

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("megarepo")
                    .withUsername(USERNAME)
                    .withPassword(PASSWORD)
                    // The project's JDBC URLs carry stringtype=unspecified so the
                    // driver sends JsonbConverter's String payloads as `unknown`
                    // and PostgreSQL infers jsonb. Without it every JSONB write
                    // fails with "column is of type jsonb but expression is of
                    // type character varying".
                    .withUrlParam("stringtype", "unspecified")
                    .waitingFor(Wait.forListeningPort());

    static {
        POSTGRES.start();
        awaitJdbcReady();
    }

    private PostgresTestSupport() {}

    /**
     * PostgreSQLContainer's readiness check passes as soon as the server logs
     * "ready to accept connections" <em>inside</em> the container. On runtimes
     * where containers live in a VM (Rancher Desktop, Colima, Docker Desktop)
     * the host-side port forward can lag behind that by a moment, so the first
     * connection from the test JVM is refused. Poll until a real JDBC
     * connection succeeds rather than let that race decide whether the suite
     * passes.
     */
    private static void awaitJdbcReady() {
        long deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
        SQLException last = null;
        while (System.nanoTime() < deadline) {
            try (Connection ignored = DriverManager.getConnection(defaultJdbcUrl(), USERNAME, PASSWORD)) {
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
        throw new IllegalStateException("PostgreSQL container never became reachable at " + defaultJdbcUrl(), last);
    }

    /** JDBC URL of the container's default database. */
    public static String defaultJdbcUrl() {
        return POSTGRES.getJdbcUrl();
    }

    /**
     * Drops and recreates {@code name} inside the shared container and returns its
     * JDBC URL, so each migration test starts from a genuinely empty database
     * instead of relying on {@code flyway.clean()}.
     */
    public static String freshDatabase(String name) {
        try (Connection connection = DriverManager.getConnection(defaultJdbcUrl(), USERNAME, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + name);
            statement.execute("CREATE DATABASE " + name);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to create test database " + name, e);
        }
        return "jdbc:postgresql://%s:%d/%s?stringtype=unspecified"
                .formatted(POSTGRES.getHost(), POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT), name);
    }

    public static Connection connect(String jdbcUrl) throws SQLException {
        return DriverManager.getConnection(jdbcUrl, USERNAME, PASSWORD);
    }
}
