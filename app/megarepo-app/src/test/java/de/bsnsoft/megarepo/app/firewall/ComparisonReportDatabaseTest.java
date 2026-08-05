package de.bsnsoft.megarepo.app.firewall;

import de.bsnsoft.megarepo.database.config.JpaConfig;
import de.bsnsoft.megarepo.repository.firewall.identity.PurlMapper;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
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
 * Base class for the CPE/purl comparison report tests.
 *
 * <h2>Why this module and not {@code megarepo-repository}</h2>
 *
 * The report turns stored components into purls, and the per-format
 * {@link PurlMapper}s that do that live in the six format modules. Those depend
 * on {@code megarepo-repository}, so it cannot depend back on them; they are
 * {@code runtimeOnly} dependencies of {@code megarepo-app} and therefore on this
 * module's test runtime classpath exactly as they are in the shipped
 * application. Substituting hand-written mappers would make the report's
 * headline claim — Maven's groupId is part of the identity, raw files have no
 * identity at all — a property of the test fixture instead of the product.
 *
 * <h2>Why a real database</h2>
 *
 * Both sides of the comparison are queries. The legacy path matches on a CPE
 * product name with an {@code IN} list, the purl path matches on a composite
 * index with a NULL-able namespace column, and Hibernate validates both entity
 * models against the real migrations. A mocked repository would let the report
 * agree with itself.
 *
 * <p>Nothing here reaches the internet beyond pulling {@code postgres:16-alpine},
 * the image {@code app/docker-compose.yml} pins. No advisory source is in the
 * context: the two remote ones are scanned out, and the report itself holds no
 * HTTP client.
 */
@SpringBootTest(
        classes = ComparisonReportDatabaseTest.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
public abstract class ComparisonReportDatabaseTest {

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
     * The firewall's read path and nothing else.
     *
     * <p>The format modules are scanned for {@link PurlMapper} implementations
     * only — a full scan of {@code de.bsnsoft.megarepo.format} would drag in
     * their storage, database and request-handling beans, which have nothing to
     * do with the report and would fail in a context that deliberately has no
     * blob store. {@code PythonNameNormalizer} is named explicitly because it is
     * the one collaborator a mapper has.
     *
     * <p>The advisory package is scanned like {@code AdvisoryDatabaseTest} does
     * it: OSV and GHSA excluded, because their HTTP clients and configuration
     * properties are not part of this context and their absence is the point —
     * the report answers from local tables only.
     */
    @Configuration(proxyBeanMethods = false)
    @Import(JpaConfig.class)
    @ComponentScan(
            basePackages = {
                "de.bsnsoft.megarepo.repository.firewall",
                "de.bsnsoft.megarepo.repository.advisory"
            },
            excludeFilters =
                    @ComponentScan.Filter(
                            type = FilterType.REGEX,
                            pattern = "de\\.bsnsoft\\.megarepo\\.repository\\.advisory\\.(osv|ghsa)\\..*"))
    @ComponentScan(
            basePackages = "de.bsnsoft.megarepo.format",
            useDefaultFilters = false,
            includeFilters = {
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = PurlMapper.class),
                @ComponentScan.Filter(
                        type = FilterType.REGEX,
                        pattern = "de\\.bsnsoft\\.megarepo\\.format\\.pypi\\.naming\\.PythonNameNormalizer")
            })
    @ImportAutoConfiguration({
        DataSourceAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class,
        org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration.class
    })
    static class TestConfig {}
}
