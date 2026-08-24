package de.bsnsoft.megarepo.database;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Minimal Spring Boot application for this module's tests. megarepo-database has
 * no application class of its own; production wiring lives in megarepo-app,
 * which this module must not depend on.
 *
 * <p>Component scanning from this package picks up
 * {@link de.bsnsoft.megarepo.database.config.JpaConfig}, so entity scanning and
 * repository registration are exactly what production uses.
 */
@SpringBootApplication
public class DatabaseTestApplication {
}
