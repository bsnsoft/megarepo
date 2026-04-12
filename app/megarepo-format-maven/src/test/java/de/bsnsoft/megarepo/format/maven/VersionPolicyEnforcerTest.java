package de.bsnsoft.megarepo.format.maven;

import de.bsnsoft.megarepo.core.repository.RepositoryConfig;
import de.bsnsoft.megarepo.core.repository.RepositoryType;
import de.bsnsoft.megarepo.format.maven.policy.ValidationException;
import de.bsnsoft.megarepo.format.maven.policy.VersionPolicyEnforcer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VersionPolicyEnforcerTest {

    private VersionPolicyEnforcer enforcer;

    @BeforeEach
    void setUp() {
        enforcer = new VersionPolicyEnforcer();
    }

    @Test
    void releasePolicy_releaseVersion_ok() {
        RepositoryConfig repo = repoWithPolicy("RELEASE");

        assertDoesNotThrow(() -> enforcer.enforceVersionPolicy(repo, "1.0.0"));
    }

    @Test
    void releasePolicy_snapshotVersion_rejected() {
        RepositoryConfig repo = repoWithPolicy("RELEASE");

        assertThrows(ValidationException.class, () -> enforcer.enforceVersionPolicy(repo, "1.0.0-SNAPSHOT"));
    }

    @Test
    void snapshotPolicy_snapshotVersion_ok() {
        RepositoryConfig repo = repoWithPolicy("SNAPSHOT");

        assertDoesNotThrow(() -> enforcer.enforceVersionPolicy(repo, "1.0.0-SNAPSHOT"));
    }

    @Test
    void snapshotPolicy_releaseVersion_rejected() {
        RepositoryConfig repo = repoWithPolicy("SNAPSHOT");

        assertThrows(ValidationException.class, () -> enforcer.enforceVersionPolicy(repo, "1.0.0"));
    }

    @Test
    void mixedPolicy_bothVersionsAllowed() {
        RepositoryConfig repo = repoWithPolicy("MIXED");

        assertDoesNotThrow(() -> enforcer.enforceVersionPolicy(repo, "1.0.0"));
        assertDoesNotThrow(() -> enforcer.enforceVersionPolicy(repo, "1.0.0-SNAPSHOT"));
    }

    @Test
    void noPolicy_defaultsMixed_bothAllowed() {
        RepositoryConfig repo = new RepositoryConfig(
                UUID.randomUUID(), "test-repo", "maven2", RepositoryType.HOSTED, true, "default", Map.of());

        assertDoesNotThrow(() -> enforcer.enforceVersionPolicy(repo, "1.0.0"));
        assertDoesNotThrow(() -> enforcer.enforceVersionPolicy(repo, "1.0.0-SNAPSHOT"));
    }

    private RepositoryConfig repoWithPolicy(String versionPolicy) {
        return new RepositoryConfig(
                UUID.randomUUID(),
                "test-repo",
                "maven2",
                RepositoryType.HOSTED,
                true,
                "default",
                Map.of("maven", Map.of("versionPolicy", versionPolicy)));
    }
}
