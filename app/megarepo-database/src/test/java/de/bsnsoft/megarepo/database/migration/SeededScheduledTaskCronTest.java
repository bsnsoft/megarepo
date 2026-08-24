package de.bsnsoft.megarepo.database.migration;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.support.CronExpression;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A scheduled task whose cron expression Spring cannot parse never gets a next run and
 * therefore never executes — silently. This guards every task seeded by a Flyway migration,
 * including the ones added after this test was written (osTicket #155155).
 */
class SeededScheduledTaskCronTest {

    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");

    /** Six whitespace-separated cron-ish fields, upper case only (MON-FRI, JAN, ...). */
    private static final Pattern CRON_LITERAL = Pattern.compile(
            "'([0-9*/,\\-?A-Z]+(?: [0-9*/,\\-?A-Z]+){5})'");

    @Test
    void everySeededCronExpressionIsParseable() throws IOException {
        var found = new ArrayList<String>();

        for (var migration : migrationFiles()) {
            var sql = Files.readString(migration, StandardCharsets.UTF_8);
            for (var statement : sql.split(";")) {
                if (!statement.toLowerCase().contains("into scheduled_tasks")) {
                    continue;
                }
                var matcher = CRON_LITERAL.matcher(statement);
                while (matcher.find()) {
                    var cron = matcher.group(1);
                    found.add(cron);
                    assertTrue(CronExpression.isValidExpression(cron),
                            "Migration " + migration.getFileName() + " seeds an unparseable cron "
                                    + "expression '" + cron + "' — the task would never run");
                }
            }
        }

        // Non-vacuity: the scan must actually have seen the seeded expressions.
        assertTrue(found.size() >= 4,
                "expected to find the seeded cron expressions, found " + found);
    }

    private List<Path> migrationFiles() throws IOException {
        assertTrue(Files.isDirectory(MIGRATIONS), "migration directory not found: " + MIGRATIONS.toAbsolutePath());
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".sql")).sorted().toList();
        }
    }
}
