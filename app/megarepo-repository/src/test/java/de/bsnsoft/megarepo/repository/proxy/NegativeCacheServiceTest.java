package de.bsnsoft.megarepo.repository.proxy;

import de.bsnsoft.megarepo.core.repository.RepositoryConfig;
import de.bsnsoft.megarepo.core.repository.RepositoryType;
import de.bsnsoft.megarepo.database.entity.NegativeCacheEntry;
import de.bsnsoft.megarepo.database.repository.NegativeCacheJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NegativeCacheServiceTest {

    @Mock
    private NegativeCacheJpaRepository negativeCacheRepository;

    private NegativeCacheService service;

    @BeforeEach
    void setUp() {
        service = new NegativeCacheService(negativeCacheRepository);
    }

    @Test
    void cacheAndCheck_validEntry_returnsTrue() {
        UUID repoId = UUID.randomUUID();
        String path = "com/example/missing-1.0.jar";

        NegativeCacheEntry entry = new NegativeCacheEntry();
        entry.setRepositoryId(repoId);
        entry.setPath(path);
        entry.setCachedAt(Instant.now());
        entry.setExpiresAt(Instant.now().plus(60, ChronoUnit.MINUTES));

        when(negativeCacheRepository.findByRepositoryIdAndPath(repoId, path)).thenReturn(Optional.of(entry));

        assertTrue(service.isNegativelyCached(repoId, path));
    }

    @Test
    void expiredEntry_returnsFalseAndDeletes() {
        UUID repoId = UUID.randomUUID();
        String path = "com/example/missing-1.0.jar";

        NegativeCacheEntry entry = new NegativeCacheEntry();
        entry.setRepositoryId(repoId);
        entry.setPath(path);
        entry.setCachedAt(Instant.now().minus(120, ChronoUnit.MINUTES));
        entry.setExpiresAt(Instant.now().minus(60, ChronoUnit.MINUTES));

        when(negativeCacheRepository.findByRepositoryIdAndPath(repoId, path)).thenReturn(Optional.of(entry));

        assertFalse(service.isNegativelyCached(repoId, path));
        verify(negativeCacheRepository).delete(entry);
    }

    @Test
    void noEntry_returnsFalse() {
        UUID repoId = UUID.randomUUID();
        String path = "com/example/nothing.jar";

        when(negativeCacheRepository.findByRepositoryIdAndPath(repoId, path)).thenReturn(Optional.empty());

        assertFalse(service.isNegativelyCached(repoId, path));
        verify(negativeCacheRepository, never()).delete(any());
    }

    @Test
    void cacheNegativeResult_createsEntry() {
        UUID repoId = UUID.randomUUID();
        String path = "com/example/artifact-1.0.jar";

        when(negativeCacheRepository.findByRepositoryIdAndPath(repoId, path)).thenReturn(Optional.empty());

        service.cacheNegativeResult(repoId, path, 60);

        ArgumentCaptor<NegativeCacheEntry> captor = ArgumentCaptor.forClass(NegativeCacheEntry.class);
        verify(negativeCacheRepository).save(captor.capture());

        NegativeCacheEntry saved = captor.getValue();
        assertEquals(repoId, saved.getRepositoryId());
        assertEquals(path, saved.getPath());
        assertTrue(saved.getExpiresAt().isAfter(Instant.now()));
    }

    @Test
    void cacheNegativeResult_updatesExistingEntry() {
        UUID repoId = UUID.randomUUID();
        String path = "com/example/artifact-1.0.jar";

        NegativeCacheEntry existing = new NegativeCacheEntry();
        existing.setRepositoryId(repoId);
        existing.setPath(path);
        existing.setCachedAt(Instant.now().minus(120, ChronoUnit.MINUTES));
        existing.setExpiresAt(Instant.now().minus(60, ChronoUnit.MINUTES));

        when(negativeCacheRepository.findByRepositoryIdAndPath(repoId, path)).thenReturn(Optional.of(existing));

        service.cacheNegativeResult(repoId, path, 30);

        ArgumentCaptor<NegativeCacheEntry> captor = ArgumentCaptor.forClass(NegativeCacheEntry.class);
        verify(negativeCacheRepository).save(captor.capture());

        NegativeCacheEntry saved = captor.getValue();
        assertTrue(saved.getExpiresAt().isAfter(Instant.now()));
    }

    @Test
    void purgeExpired_deletesExpiredEntries() {
        service.purgeExpired();
        verify(negativeCacheRepository).deleteByExpiresAtBefore(any(Instant.class));
    }

    @Test
    void isEnabled_defaultTrue() {
        RepositoryConfig repo = createProxyRepo(Map.of());
        assertTrue(service.isEnabled(repo));
    }

    @Test
    void isEnabled_configuredFalse() {
        RepositoryConfig repo = createProxyRepo(Map.of("negativeCache", Map.of("enabled", false)));
        assertFalse(service.isEnabled(repo));
    }

    @Test
    void getTtl_defaultValue() {
        RepositoryConfig repo = createProxyRepo(Map.of());
        assertEquals(1440, service.getNegativeCacheTtl(repo));
    }

    @Test
    void getTtl_configuredValue() {
        RepositoryConfig repo = createProxyRepo(Map.of("negativeCache", Map.of("timeToLive", 30)));
        assertEquals(30, service.getNegativeCacheTtl(repo));
    }

    private RepositoryConfig createProxyRepo(Map<String, Object> attributes) {
        return new RepositoryConfig(
                UUID.randomUUID(), "proxy-repo", "maven2", RepositoryType.PROXY, true, "default", attributes);
    }
}
