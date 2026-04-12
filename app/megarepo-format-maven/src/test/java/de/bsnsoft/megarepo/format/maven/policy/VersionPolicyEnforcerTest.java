package de.bsnsoft.megarepo.format.maven.policy;

import de.bsnsoft.megarepo.core.repository.RepositoryConfig;
import de.bsnsoft.megarepo.core.repository.RepositoryType;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionPolicyEnforcerTest {

    private final VersionPolicyEnforcer enforcer = new VersionPolicyEnforcer();

    private RepositoryConfig repoWithPolicy(String policy) {
        return new RepositoryConfig(
                UUID.randomUUID(),
                "test-repo",
                "maven2",
                RepositoryType.HOSTED,
                true,
                "default",
                Map.of("maven", Map.of("versionPolicy", policy)));
    }

    // --- Release policy ---

    @Test
    void releasePolicy_allowsReleaseVersion() {
        assertDoesNotThrow(() -> enforcer.enforceVersionPolicy(repoWithPolicy("RELEASE"), "1.0.0"));
    }

    @Test
    void releasePolicy_allowsMultiDigitRelease() {
        assertDoesNotThrow(() -> enforcer.enforceVersionPolicy(repoWithPolicy("RELEASE"), "3.14.0"));
    }

    @Test
    void releasePolicy_allowsQualifiedRelease() {
        assertDoesNotThrow(() -> enforcer.enforceVersionPolicy(repoWithPolicy("RELEASE"), "1.0.0-beta1"));
    }

    @Test
    void releasePolicy_allowsRcRelease() {
        assertDoesNotThrow(() -> enforcer.enforceVersionPolicy(repoWithPolicy("RELEASE"), "2.0.0-RC1"));
    }

    @Test
    void releasePolicy_rejectsSnapshot() {
        assertThrows(
                ValidationException.class,
                () -> enforcer.enforceVersionPolicy(repoWithPolicy("RELEASE"), "1.0.0-SNAPSHOT"));
    }

    // --- Snapshot policy ---

    @Test
    void snapshotPolicy_allowsSnapshot() {
        assertDoesNotThrow(() -> enforcer.enforceVersionPolicy(repoWithPolicy("SNAPSHOT"), "1.0.0-SNAPSHOT"));
    }

    @Test
    void snapshotPolicy_rejectsRelease() {
        assertThrows(
                ValidationException.class,
                () -> enforcer.enforceVersionPolicy(repoWithPolicy("SNAPSHOT"), "1.0.0"));
    }

    // --- Mixed policy ---

    @Test
    void mixedPolicy_allowsRelease() {
        assertDoesNotThrow(() -> enforcer.enforceVersionPolicy(repoWithPolicy("MIXED"), "1.0.0"));
    }

    @Test
    void mixedPolicy_allowsSnapshot() {
        assertDoesNotThrow(() -> enforcer.enforceVersionPolicy(repoWithPolicy("MIXED"), "1.0.0-SNAPSHOT"));
    }

    // --- isSnapshotVersion ---

    @Test
    void isSnapshotVersion_detectsStandardSnapshot() {
        assertTrue(enforcer.isSnapshotVersion("1.0.0-SNAPSHOT"));
    }

    @Test
    void isSnapshotVersion_rejectsRelease() {
        assertFalse(enforcer.isSnapshotVersion("1.0.0"));
    }

    @Test
    void isSnapshotVersion_rejectsNull() {
        assertFalse(enforcer.isSnapshotVersion(null));
    }

    @Test
    void isSnapshotVersion_rejectsEmpty() {
        assertFalse(enforcer.isSnapshotVersion(""));
    }
}
