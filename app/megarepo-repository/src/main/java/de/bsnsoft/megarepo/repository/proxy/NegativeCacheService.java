package de.bsnsoft.megarepo.repository.proxy;

import de.bsnsoft.megarepo.core.repository.RepositoryConfig;
import de.bsnsoft.megarepo.database.entity.NegativeCacheEntry;
import de.bsnsoft.megarepo.database.repository.NegativeCacheJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class NegativeCacheService {

    private static final Logger log = LoggerFactory.getLogger(NegativeCacheService.class);
    private static final int DEFAULT_TTL_MINUTES = 1440;

    private final NegativeCacheJpaRepository negativeCacheRepository;

    public NegativeCacheService(NegativeCacheJpaRepository negativeCacheRepository) {
        this.negativeCacheRepository = negativeCacheRepository;
    }

    @Transactional
    public boolean isNegativelyCached(UUID repoId, String path) {
        Optional<NegativeCacheEntry> entry = negativeCacheRepository.findByRepositoryIdAndPath(repoId, path);
        if (entry.isEmpty()) {
            return false;
        }
        NegativeCacheEntry cached = entry.get();
        if (cached.getExpiresAt().isBefore(Instant.now())) {
            // Expired - clean up lazily
            negativeCacheRepository.delete(cached);
            return false;
        }
        return true;
    }

    @Transactional
    public void cacheNegativeResult(UUID repoId, String path, int ttlMinutes) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(ttlMinutes, ChronoUnit.MINUTES);

        Optional<NegativeCacheEntry> existing = negativeCacheRepository.findByRepositoryIdAndPath(repoId, path);
        NegativeCacheEntry entry;
        if (existing.isPresent()) {
            entry = existing.get();
        } else {
            entry = new NegativeCacheEntry();
            entry.setRepositoryId(repoId);
            entry.setPath(path);
        }
        entry.setCachedAt(now);
        entry.setExpiresAt(expiresAt);
        negativeCacheRepository.save(entry);

        log.debug("Negative cache entry stored for repo={} path={} ttl={}min", repoId, path, ttlMinutes);
    }

    @Transactional
    public void purgeExpired() {
        negativeCacheRepository.deleteByExpiresAtBefore(Instant.now());
    }

    @SuppressWarnings("unchecked")
    public int getNegativeCacheTtl(RepositoryConfig repo) {
        Object ncObj = repo.attributes().get("negativeCache");
        if (ncObj instanceof Map<?, ?> ncMap) {
            Object ttl = ncMap.get("timeToLive");
            if (ttl instanceof Number n) {
                return n.intValue();
            }
        }
        return DEFAULT_TTL_MINUTES;
    }

    @SuppressWarnings("unchecked")
    public boolean isEnabled(RepositoryConfig repo) {
        Object ncObj = repo.attributes().get("negativeCache");
        if (ncObj instanceof Map<?, ?> ncMap) {
            Object enabled = ncMap.get("enabled");
            if (enabled instanceof Boolean b) {
                return b;
            }
        }
        return true;
    }
}
