package de.bsnsoft.megarepo.repository.proxy;

import de.bsnsoft.megarepo.core.repository.RepositoryConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@Service
public class BlacklistService {

    private static final Logger log = LoggerFactory.getLogger(BlacklistService.class);

    private final ConcurrentHashMap<UUID, List<Pattern>> compiledPatternsCache = new ConcurrentHashMap<>();

    public boolean isBlacklisted(RepositoryConfig repo, String path) {
        List<String> rawPatterns = getBlacklistPatterns(repo);
        if (rawPatterns.isEmpty()) {
            return false;
        }

        List<Pattern> patterns = compiledPatternsCache.computeIfAbsent(repo.id(), key -> compilePatterns(rawPatterns));
        return patterns.stream().anyMatch(pattern -> pattern.matcher(path).matches());
    }

    public void invalidateCache(UUID repoId) {
        compiledPatternsCache.remove(repoId);
        log.debug("Blacklist pattern cache invalidated for repo={}", repoId);
    }

    @SuppressWarnings("unchecked")
    public List<String> getBlacklistPatterns(RepositoryConfig repo) {
        Object proxyObj = repo.attributes().get("proxy");
        if (proxyObj instanceof Map<?, ?> proxyMap) {
            Object blacklistObj = proxyMap.get("blacklist");
            if (blacklistObj instanceof List<?> list) {
                return list.stream()
                        .filter(String.class::isInstance)
                        .map(String.class::cast)
                        .toList();
            }
        }
        return Collections.emptyList();
    }

    private List<Pattern> compilePatterns(List<String> rawPatterns) {
        return rawPatterns.stream()
                .map(raw -> {
                    try {
                        return Pattern.compile(raw);
                    } catch (PatternSyntaxException e) {
                        log.warn("Invalid blacklist regex pattern '{}': {}", raw, e.getMessage());
                        return null;
                    }
                })
                .filter(p -> p != null)
                .toList();
    }
}
