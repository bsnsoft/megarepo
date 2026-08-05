package de.bsnsoft.megarepo.database.entity;

import de.bsnsoft.megarepo.database.PostgresTestSupport;
import de.bsnsoft.megarepo.database.repository.FirewallEnforcementSettingsJpaRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The global enforcement switch against the real migrated schema.
 *
 * <p>Runs with {@code ddl-auto=validate} like the rest of the entity tests, so
 * a disagreement between {@link FirewallEnforcementSettingsEntity} and V17 fails
 * the context rather than surfacing as a runtime error on the one endpoint that
 * can turn blocking on.
 *
 * <p>Beyond the mapping, two properties are asserted here because they are
 * safety guarantees rather than behaviour: the migration seeds the switch
 * <em>off</em>, and the table cannot hold a second row for something else to
 * read.
 *
 * <p>Nothing here reaches the internet beyond pulling {@code postgres:16-alpine},
 * the image {@code app/docker-compose.yml} pins.
 */
@SpringBootTest
@Transactional
class FirewallEnforcementSettingsMappingTest {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresTestSupport::defaultJdbcUrl);
        registry.add("spring.datasource.username", () -> PostgresTestSupport.USERNAME);
        registry.add("spring.datasource.password", () -> PostgresTestSupport.PASSWORD);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
        registry.add("spring.flyway.placeholder-replacement", () -> "false");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.open-in-view", () -> "false");
    }

    @Autowired private FirewallEnforcementSettingsJpaRepository enforcement;
    @Autowired private EntityManager entityManager;

    @Test
    @DisplayName("the migration seeds exactly one row, and it is off")
    void migrationSeedsTheSwitchOff() {
        assertThat(enforcement.count()).isEqualTo(1);

        FirewallEnforcementSettingsEntity current = enforcement.current();
        assertThat(current.getId()).isEqualTo(FirewallEnforcementSettingsEntity.SINGLETON_ID);
        assertThat(current.isEnabled())
                .as("upgrading to V17 must not start blocking anything on an "
                        + "installation that has repositories set to QUARANTINE")
                .isFalse();
        assertThat(current.getUpdatedBy()).isNull();
    }

    @Test
    @DisplayName("arming round-trips, including who did it and when")
    void switchRoundTrips() {
        FirewallEnforcementSettingsEntity settings = enforcement.current();
        settings.setEnabled(true);
        settings.setUpdatedBy("admin");
        settings.setUpdatedAt(Instant.parse("2026-08-05T08:30:00Z"));
        enforcement.saveAndFlush(settings);

        entityManager.clear();

        FirewallEnforcementSettingsEntity reloaded = enforcement.current();
        assertThat(reloaded.isEnabled()).isTrue();
        assertThat(reloaded.getUpdatedBy()).isEqualTo("admin");
        assertThat(reloaded.getUpdatedAt()).isEqualTo(Instant.parse("2026-08-05T08:30:00Z"));

        // Still one row: writing the switch updates, never appends.
        assertThat(enforcement.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("a second row is impossible, so there can be no second answer")
    void theTableCannotHoldATwin() {
        assertThatThrownBy(() -> {
                    entityManager
                            .createNativeQuery(
                                    "INSERT INTO firewall_enforcement_settings (id, enabled) VALUES (2, true)")
                            .executeUpdate();
                    entityManager.flush();
                })
                .as("a CHECK (id = 1) is what keeps 'is enforcement on?' a question "
                        + "with one answer")
                .isInstanceOf(Exception.class);
    }
}
