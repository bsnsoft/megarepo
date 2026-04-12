package de.bsnsoft.megarepo.repository.proxy;

import de.bsnsoft.megarepo.core.repository.RepositoryConfig;
import de.bsnsoft.megarepo.core.repository.RepositoryType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlacklistServiceTest {

    private BlacklistService service;

    private static final UUID REPO_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new BlacklistService();
    }

    @Test
    void noPatterns_nothingBlacklisted() {
        RepositoryConfig repo = createRepo(Map.of("proxy", Map.of("remoteUrl", "https://repo.example.com")));
        assertFalse(service.isBlacklisted(repo, "com/example/artifact/1.0/artifact-1.0.jar"));
    }

    @Test
    void emptyBlacklist_nothingBlacklisted() {
        RepositoryConfig repo =
                createRepo(Map.of("proxy", Map.of("remoteUrl", "https://repo.example.com", "blacklist", List.of())));
        assertFalse(service.isBlacklisted(repo, "com/example/artifact/1.0/artifact-1.0.jar"));
    }

    @Test
    void matchingPattern_isBlacklisted() {
        RepositoryConfig repo = createRepo(
                Map.of("proxy", Map.of("remoteUrl", "https://repo.example.com", "blacklist", List.of(".*SNAPSHOT.*"))));
        assertTrue(service.isBlacklisted(repo, "com/example/artifact/1.0-SNAPSHOT/artifact-1.0-SNAPSHOT.jar"));
    }

    @Test
    void nonMatchingPattern_notBlacklisted() {
        RepositoryConfig repo = createRepo(
                Map.of("proxy", Map.of("remoteUrl", "https://repo.example.com", "blacklist", List.of(".*SNAPSHOT.*"))));
        assertFalse(service.isBlacklisted(repo, "com/example/artifact/1.0/artifact-1.0.jar"));
    }

    @Test
    void multiplePatterns_anyMatchBlocks() {
        RepositoryConfig repo = createRepo(Map.of(
                "proxy",
                Map.of(
                        "remoteUrl",
                        "https://repo.example.com",
                        "blacklist",
                        List.of(".*SNAPSHOT.*", "org/dangerous/.*", ".*\\.exe"))));

        assertTrue(service.isBlacklisted(repo, "org/dangerous/malware/1.0/malware-1.0.jar"));
        assertTrue(service.isBlacklisted(repo, "com/example/1.0-SNAPSHOT/artifact.jar"));
        assertTrue(service.isBlacklisted(repo, "some/path/file.exe"));
        assertFalse(service.isBlacklisted(repo, "com/safe/artifact/1.0/artifact-1.0.jar"));
    }

    @Test
    void invalidPattern_isSkipped() {
        RepositoryConfig repo = createRepo(Map.of(
                "proxy",
                Map.of(
                        "remoteUrl",
                        "https://repo.example.com",
                        "blacklist",
                        List.of("[invalid", ".*SNAPSHOT.*"))));

        // The invalid pattern is skipped, the valid one still works
        assertTrue(service.isBlacklisted(repo, "com/example/1.0-SNAPSHOT/artifact.jar"));
        assertFalse(service.isBlacklisted(repo, "com/example/1.0/artifact.jar"));
    }

    @Test
    void cacheInvalidation_recompilesPatterns() {
        RepositoryConfig repo1 = createRepo(
                Map.of("proxy", Map.of("remoteUrl", "https://repo.example.com", "blacklist", List.of(".*old.*"))));

        assertTrue(service.isBlacklisted(repo1, "some/old/path.jar"));

        // Invalidate and provide a new config with different patterns
        service.invalidateCache(REPO_ID);

        RepositoryConfig repo2 = new RepositoryConfig(
                REPO_ID,
                "proxy-repo",
                "maven2",
                RepositoryType.PROXY,
                true,
                "default",
                Map.of("proxy", Map.of("remoteUrl", "https://repo.example.com", "blacklist", List.of(".*new.*"))));

        assertFalse(service.isBlacklisted(repo2, "some/old/path.jar"));
        assertTrue(service.isBlacklisted(repo2, "some/new/path.jar"));
    }

    @Test
    void getBlacklistPatterns_returnsConfiguredPatterns() {
        List<String> patterns = List.of(".*SNAPSHOT.*", "org/blocked/.*");
        RepositoryConfig repo =
                createRepo(Map.of("proxy", Map.of("remoteUrl", "https://repo.example.com", "blacklist", patterns)));

        List<String> result = service.getBlacklistPatterns(repo);
        assertEquals(2, result.size());
        assertEquals(".*SNAPSHOT.*", result.get(0));
        assertEquals("org/blocked/.*", result.get(1));
    }

    @Test
    void getBlacklistPatterns_noProxyConfig_returnsEmpty() {
        RepositoryConfig repo = createRepo(Map.of());
        List<String> result = service.getBlacklistPatterns(repo);
        assertTrue(result.isEmpty());
    }

    private RepositoryConfig createRepo(Map<String, Object> attributes) {
        return new RepositoryConfig(
                REPO_ID, "proxy-repo", "maven2", RepositoryType.PROXY, true, "default", attributes);
    }
}
