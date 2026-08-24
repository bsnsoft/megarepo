package de.bsnsoft.megarepo.it;

import de.bsnsoft.megarepo.core.format.FormatRegistry;
import de.bsnsoft.megarepo.database.entity.RepositoryEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Regression test for the “Unsupported format: maven” bug.
 *
 * <p>The {@link de.bsnsoft.megarepo.app.setup.FirstRunSetup} ApplicationRunner
 * seeded the default Maven repositories with format string {@code "maven"}, but
 * {@link de.bsnsoft.megarepo.format.maven.MavenFormatPlugin#getFormat()} returns
 * {@code "maven2"} (Sonatype-Nexus convention). Result: every request against
 * a default Maven repo crashed with {@code UnsupportedFormatException} at request
 * time. The pre-existing integration tests only exercised {@code raw}, so the bug
 * shipped to production.
 *
 * <p>This test class enforces two invariants:
 * <ol>
 *   <li>Every repository in the database (including those seeded at boot) declares
 *       a format that is actually registered in {@link FormatRegistry}.</li>
 *   <li>A GET against the default {@code maven-public} group repo reaches the
 *       request pipeline (200/404 are both fine — anything except a 500 caused by
 *       an unknown format).</li>
 * </ol>
 */
class DefaultRepositoryFormatsIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private FormatRegistry formatRegistry;

    @Test
    void every_seeded_repository_has_a_registered_format_plugin() {
        Set<String> registeredFormats = formatRegistry.getSupportedFormats();
        List<RepositoryEntity> repos = repositoryJpaRepository.findAll();

        assertFalse(repos.isEmpty(),
                "Expected default repositories to have been seeded by FirstRunSetup");

        for (RepositoryEntity repo : repos) {
            assertTrue(
                    registeredFormats.contains(repo.getFormat()),
                    "Repository '" + repo.getName() + "' declares format '"
                            + repo.getFormat() + "' but no FormatPlugin is registered for it. "
                            + "Registered formats: " + registeredFormats);
        }
    }

    @Test
    void default_maven_public_group_repo_does_not_500_on_request() {
        // Pick the canonical metadata path — guaranteed to invoke the FormatRegistry
        // lookup via GroupHandler#handleGet.
        String url = repositoryUrl("maven-public", "org/example/lib/maven-metadata.xml");
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, null, String.class);

        // 404 is the expected outcome (artifact not present); 200 is fine if the
        // proxy happens to satisfy it. Anything in the 5xx range means the format
        // wiring is broken again.
        assertNotEquals(
                HttpStatus.INTERNAL_SERVER_ERROR,
                response.getStatusCode(),
                "GET /repository/maven-public/... returned 500 — the maven plugin is "
                        + "not registered for the default seeded repository's format. "
                        + "Body: " + response.getBody());

        if (response.getStatusCode().is5xxServerError()) {
            fail("Unexpected 5xx from /repository/maven-public/...: "
                    + response.getStatusCode() + " — body: " + response.getBody());
        }
    }
}
