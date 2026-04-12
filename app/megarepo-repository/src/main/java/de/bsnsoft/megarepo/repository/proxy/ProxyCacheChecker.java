package de.bsnsoft.megarepo.repository.proxy;

import de.bsnsoft.megarepo.core.repository.RepositoryConfig;
import de.bsnsoft.megarepo.database.entity.AssetEntity;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

@Component
public class ProxyCacheChecker {

    private static final int DEFAULT_CONTENT_MAX_AGE_MINUTES = 1440;
    private static final int DEFAULT_METADATA_MAX_AGE_MINUTES = 5;

    public boolean isExpired(AssetEntity asset, RepositoryConfig repo) {
        int maxAge = getContentMaxAge(repo);
        return isAssetExpired(asset, maxAge);
    }

    public boolean isMetadataExpired(AssetEntity asset, RepositoryConfig repo) {
        int maxAge = getMetadataMaxAge(repo);
        return isAssetExpired(asset, maxAge);
    }

    private boolean isAssetExpired(AssetEntity asset, int maxAgeMinutes) {
        Instant lastModified = asset.getLastModified();
        if (lastModified == null) {
            return true;
        }
        Instant expiresAt = lastModified.plus(maxAgeMinutes, ChronoUnit.MINUTES);
        return Instant.now().isAfter(expiresAt);
    }

    /**
     * Gets the content cache TTL in minutes. Checks for {@code cacheTtlMinutes} first
     * (the preferred attribute name), then falls back to {@code contentMaxAge} for
     * backward compatibility.
     */
    @SuppressWarnings("unchecked")
    int getContentMaxAge(RepositoryConfig repo) {
        Object proxyObj = repo.attributes().get("proxy");
        if (proxyObj instanceof Map<?, ?> proxyMap) {
            // Preferred: cacheTtlMinutes
            Object cacheTtl = proxyMap.get("cacheTtlMinutes");
            if (cacheTtl instanceof Number n) {
                return n.intValue();
            }
            // Backward compat: contentMaxAge
            Object maxAge = proxyMap.get("contentMaxAge");
            if (maxAge instanceof Number n) {
                return n.intValue();
            }
        }
        return DEFAULT_CONTENT_MAX_AGE_MINUTES;
    }

    /**
     * Gets the metadata cache TTL in minutes. Checks for {@code metadataCacheTtlMinutes} first,
     * then falls back to {@code metadataMaxAge} for backward compatibility. Defaults to 5 minutes
     * since metadata (maven-metadata.xml, simple/ pages) changes frequently.
     */
    @SuppressWarnings("unchecked")
    int getMetadataMaxAge(RepositoryConfig repo) {
        Object proxyObj = repo.attributes().get("proxy");
        if (proxyObj instanceof Map<?, ?> proxyMap) {
            // Preferred: metadataCacheTtlMinutes
            Object metadataTtl = proxyMap.get("metadataCacheTtlMinutes");
            if (metadataTtl instanceof Number n) {
                return n.intValue();
            }
            // Backward compat: metadataMaxAge
            Object maxAge = proxyMap.get("metadataMaxAge");
            if (maxAge instanceof Number n) {
                return n.intValue();
            }
        }
        return DEFAULT_METADATA_MAX_AGE_MINUTES;
    }
}
