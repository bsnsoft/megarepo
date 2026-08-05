package de.bsnsoft.megarepo.repository.advisory;

import de.bsnsoft.megarepo.database.config.JpaConfig;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;

/**
 * Base class for the advisory tests that need a real database.
 *
 * <p>The ingest and the lookup are only meaningful against SQL: idempotency is a
 * property of how rows are replaced, the CPE-derived pass depends on an index
 * that only exists in a migration, and namespace matching turns on PostgreSQL's
 * {@code IS NULL} semantics. Mocking the repositories would assert the mock.
 *
 * <p>Flyway runs the real migrations and Hibernate validates the entities
 * against the result — the same {@code ddl-auto=validate} production uses — so a
 * migration and an entity drifting apart fails the context, not just a query.
 *
 * <p>The container is started once per JVM and reaped by Ryuk. Nothing here
 * reaches the internet beyond pulling {@code postgres:16-alpine}, the image
 * {@code app/docker-compose.yml} pins.
 */
@SpringBootTest(
        classes = AdvisoryDatabaseTest.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
public abstract class AdvisoryDatabaseTest {

    static final String USERNAME = "megarepo";
    static final String PASSWORD = "megarepo";

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("megarepo")
                    .withUsername(USERNAME)
                    .withPassword(PASSWORD)
                    // Matches the project's JDBC URLs: without it every JSONB
                    // write fails, because the driver would send String payloads
                    // as varchar instead of letting PostgreSQL infer jsonb.
                    .withUrlParam("stringtype", "unspecified")
                    .waitingFor(Wait.forListeningPort());

    static {
        POSTGRES.start();
        awaitJdbcReady();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> USERNAME);
        registry.add("spring.datasource.password", () -> PASSWORD);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
        registry.add("spring.flyway.placeholder-replacement", () -> "false");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.open-in-view", () -> "false");
    }

    /**
     * PostgreSQLContainer reports readiness from the container's own log, but on
     * runtimes where containers live in a VM (Rancher Desktop, Colima, Docker
     * Desktop) the host port forward can lag behind that. Poll for a real JDBC
     * connection rather than let the race decide whether the suite passes.
     */
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

    /**
     * Only the advisory beans and the database module's JPA wiring.
     *
     * <p>megarepo-repository also carries the web and security starters, and a
     * plain {@code @SpringBootApplication} would auto-configure both. Listing the
     * three auto-configurations that are actually needed keeps the context small
     * and the failure modes obvious.
     */
    @Configuration(proxyBeanMethods = false)
    @Import(JpaConfig.class)
    @ComponentScan(basePackageClasses = AdvisoryIngestService.class)
    @ImportAutoConfiguration({
        DataSourceAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class,
        org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration.class
    })
    static class TestConfig {}
}
