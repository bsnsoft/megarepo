package de.bsnsoft.megarepo.format.nuget.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.bsnsoft.megarepo.core.repository.RepositoryConfig;
import de.bsnsoft.megarepo.repository.proxy.RemoteHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves and caches the upstream NuGet V3 service index for proxy
 * repositories. The configured {@code proxy.remoteUrl} is the upstream
 * service index URL itself (e.g. {@code https://api.nuget.org/v3/index.json});
 * the individual resource base URLs (flat container, registrations, search)
 * are discovered from it and cached in memory using the repository's
 * metadata TTL.
 */
@Component
public class UpstreamServiceIndexResolver {

    private static final Logger log = LoggerFactory.getLogger(UpstreamServiceIndexResolver.class);
    private static final int DEFAULT_METADATA_TTL_MINUTES = 5;

    /** Resource base URLs of an upstream V3 feed, trailing slashes stripped. */
    public record UpstreamResources(String flatContainerBase, String registrationsBase, String searchBase) {}

    private record CacheEntry(UpstreamResources resources, Instant fetchedAt) {}

    private final RemoteHttpClient remoteHttpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConcurrentHashMap<UUID, CacheEntry> cache = new ConcurrentHashMap<>();

    public UpstreamServiceIndexResolver(RemoteHttpClient remoteHttpClient) {
        this.remoteHttpClient = remoteHttpClient;
    }

    public Optional<UpstreamResources> resolve(RepositoryConfig repo) {
        CacheEntry cached = cache.get(repo.id());
        if (cached != null && !isExpired(cached, repo)) {
            return Optional.of(cached.resources());
        }

        String indexUrl = remoteIndexUrl(repo);
        if (indexUrl == null) {
            log.warn("NuGet proxy repository '{}' has no remoteUrl configured", repo.name());
            return Optional.empty();
        }

        try {
            Optional<UpstreamResources> fetched = fetchServiceIndex(repo, indexUrl);
            fetched.ifPresent(resources -> cache.put(repo.id(), new CacheEntry(resources, Instant.now())));
            if (fetched.isEmpty() && cached != null) {
                // Upstream hiccup: keep serving the stale resource map
                return Optional.of(cached.resources());
            }
            return fetched;
        } catch (IOException e) {
            log.warn("Failed to fetch upstream service index {} for repo={}: {}",
                    indexUrl, repo.name(), e.getMessage());
            return cached != null ? Optional.of(cached.resources()) : Optional.empty();
        }
    }

    /** Drops the cached resource map (used by tests). */
    public void invalidate(UUID repoId) {
        cache.remove(repoId);
    }

    String remoteIndexUrl(RepositoryConfig repo) {
        Object proxyObj = repo.attributes().get("proxy");
        if (proxyObj instanceof Map<?, ?> proxyMap && proxyMap.get("remoteUrl") instanceof String url) {
            return url;
        }
        return null;
    }

    private Optional<UpstreamResources> fetchServiceIndex(RepositoryConfig repo, String indexUrl)
            throws IOException {
        RemoteHttpClient.RemoteResponse response = fetchWithRepoAuth(repo, indexUrl);
        if (response.statusCode() != 200 || response.body() == null) {
            closeQuietly(response.body());
            log.warn("Upstream service index {} returned status {} for repo={}",
                    indexUrl, response.statusCode(), repo.name());
            return Optional.empty();
        }

        JsonNode root;
        try (InputStream body = response.body()) {
            root = objectMapper.readTree(body);
        }

        String flatContainer = null;
        String registrations = null;
        String registrationsPreferred = null;
        String search = null;

        JsonNode resources = root.path("resources");
        for (JsonNode resource : resources) {
            String type = resource.path("@type").asText("");
            String id = resource.path("@id").asText("");
            if (id.isEmpty()) {
                continue;
            }
            switch (type) {
                case "PackageBaseAddress/3.0.0" -> flatContainer = id;
                case "SearchQueryService" -> search = (search == null) ? id : search;
                case "RegistrationsBaseUrl" -> registrations = (registrations == null) ? id : registrations;
                // Prefer the semver2 registration hives so pre-release/semver2
                // packages resolve; plain RegistrationsBaseUrl is semver1-only.
                case "RegistrationsBaseUrl/3.6.0", "RegistrationsBaseUrl/Versioned" ->
                        registrationsPreferred = id;
                default -> { /* irrelevant resource */ }
            }
        }
        if (registrationsPreferred != null) {
            registrations = registrationsPreferred;
        }

        if (flatContainer == null) {
            log.warn("Upstream service index {} has no PackageBaseAddress resource", indexUrl);
            return Optional.empty();
        }

        return Optional.of(new UpstreamResources(
                stripTrailingSlash(flatContainer),
                registrations != null ? stripTrailingSlash(registrations) : null,
                search != null ? stripTrailingSlash(search) : null));
    }

    /** Fetches a URL applying the repository's upstream credentials, if configured. */
    public RemoteHttpClient.RemoteResponse fetchWithRepoAuth(RepositoryConfig repo, String url) throws IOException {
        Object proxyObj = repo.attributes().get("proxy");
        if (proxyObj instanceof Map<?, ?> proxyMap
                && proxyMap.get("username") instanceof String username
                && !username.isBlank()
                && proxyMap.get("password") instanceof String password) {
            return remoteHttpClient.fetchWithAuth(url, username, password);
        }
        return remoteHttpClient.fetch(url, Map.of());
    }

    private boolean isExpired(CacheEntry entry, RepositoryConfig repo) {
        int ttlMinutes = metadataTtlMinutes(repo);
        return Instant.now().isAfter(entry.fetchedAt().plus(ttlMinutes, ChronoUnit.MINUTES));
    }

    private int metadataTtlMinutes(RepositoryConfig repo) {
        Object proxyObj = repo.attributes().get("proxy");
        if (proxyObj instanceof Map<?, ?> proxyMap
                && proxyMap.get("metadataCacheTtlMinutes") instanceof Number ttl) {
            return ttl.intValue();
        }
        return DEFAULT_METADATA_TTL_MINUTES;
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static void closeQuietly(InputStream in) {
        if (in != null) {
            try {
                in.close();
            } catch (IOException ignored) {
                // best effort
            }
        }
    }
}
