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
import java.util.HashMap;
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

        // The NuGet V3 protocol versions its resource types: "SearchQueryService",
        // "SearchQueryService/3.0.0-beta" and "SearchQueryService/3.5.0" all denote
        // the same resource in different protocol revisions, and a feed is free to
        // publish only the versioned spellings. Group every entry by its base type
        // (the part before the first slash) and pick one winner per base type.
        Map<String, ResourceCandidate> byBaseType = new HashMap<>();
        int order = 0;

        JsonNode resources = root.path("resources");
        for (JsonNode resource : resources) {
            String type = resource.path("@type").asText("");
            String id = resource.path("@id").asText("");
            if (id.isEmpty() || type.isEmpty()) {
                continue;
            }
            ResourceCandidate candidate = ResourceCandidate.parse(type, id, order++);
            byBaseType.merge(candidate.baseType(), candidate, ResourceCandidate::preferred);
        }

        String flatContainer = idOf(byBaseType.get("PackageBaseAddress"));
        String registrations = idOf(byBaseType.get("RegistrationsBaseUrl"));
        String search = idOf(byBaseType.get("SearchQueryService"));

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

    private static String idOf(ResourceCandidate candidate) {
        return candidate != null ? candidate.id() : null;
    }

    /**
     * One {@code resources[]} entry of a service index, split into its base
     * {@code @type} and the optional variant suffix behind the first slash
     * ({@code SearchQueryService/3.5.0} → base {@code SearchQueryService},
     * suffix {@code 3.5.0}; {@code SearchQueryService} → suffix {@code ""}).
     *
     * @param order position in the {@code resources} array, used only to break
     *              ties between otherwise equally ranked entries
     */
    record ResourceCandidate(String baseType, String suffix, String id, int order) {

        /** Suffix ranks — higher wins. See {@link #preferred(ResourceCandidate)}. */
        private static final int RANK_UNVERSIONED = 0;
        private static final int RANK_VERSIONED = 1;
        private static final int RANK_NAMED = 2;

        static ResourceCandidate parse(String type, String id, int order) {
            int slash = type.indexOf('/');
            if (slash < 0) {
                return new ResourceCandidate(type, "", id, order);
            }
            return new ResourceCandidate(
                    type.substring(0, slash), type.substring(slash + 1), id, order);
        }

        /**
         * Deterministic winner between two entries of the same base type. The
         * order is independent of the position in the JSON document except for
         * genuinely equal spellings:
         *
         * <ol>
         *   <li>a named suffix ({@code RegistrationsBaseUrl/Versioned}) beats
         *       everything — NuGet uses it for the newest hive of a resource,
         *       and the previous implementation already treated it as the top
         *       choice for registrations;</li>
         *   <li>otherwise the highest numeric version wins
         *       ({@code /3.6.0} &gt; {@code /3.4.0} &gt; {@code /3.0.0-beta}),
         *       a pre-release losing against its own release;</li>
         *   <li>an unversioned {@code @type} ranks lowest: it denotes the
         *       oldest protocol revision (plain {@code RegistrationsBaseUrl}
         *       is semver1-only), but it still resolves when it is the only
         *       spelling the feed publishes;</li>
         *   <li>on a full tie the entry listed first wins, which keeps the
         *       "primary before secondary" convention of duplicated endpoints.</li>
         * </ol>
         */
        ResourceCandidate preferred(ResourceCandidate other) {
            int cmp = Integer.compare(rank(), other.rank());
            if (cmp == 0 && rank() == RANK_VERSIONED) {
                cmp = compareVersions(suffix, other.suffix);
            } else if (cmp == 0 && rank() == RANK_NAMED) {
                cmp = suffix.compareToIgnoreCase(other.suffix);
            }
            if (cmp != 0) {
                return cmp > 0 ? this : other;
            }
            return order <= other.order ? this : other;
        }

        private int rank() {
            if (suffix.isEmpty()) {
                return RANK_UNVERSIONED;
            }
            return isNumericVersion(suffix) ? RANK_VERSIONED : RANK_NAMED;
        }

        /** {@code true} for {@code 3.6.0} and {@code 3.0.0-beta}, false for {@code Versioned}. */
        private static boolean isNumericVersion(String suffix) {
            for (String part : releasePart(suffix).split("\\.", -1)) {
                // The length cap keeps segment() free of overflow surprises.
                if (part.isEmpty() || part.length() > 9 || !part.chars().allMatch(Character::isDigit)) {
                    return false;
                }
            }
            return true;
        }

        /** Compares two numeric version suffixes; a pre-release sorts below its release. */
        private static int compareVersions(String left, String right) {
            String[] leftParts = releasePart(left).split("\\.", -1);
            String[] rightParts = releasePart(right).split("\\.", -1);
            for (int i = 0; i < Math.max(leftParts.length, rightParts.length); i++) {
                int cmp = Integer.compare(segment(leftParts, i), segment(rightParts, i));
                if (cmp != 0) {
                    return cmp;
                }
            }
            String leftPre = preReleasePart(left);
            String rightPre = preReleasePart(right);
            if (leftPre.isEmpty() || rightPre.isEmpty()) {
                // A release (no pre-release tag) outranks its own pre-releases.
                return Integer.compare(leftPre.isEmpty() ? 1 : 0, rightPre.isEmpty() ? 1 : 0);
            }
            return leftPre.compareToIgnoreCase(rightPre);
        }

        private static int segment(String[] parts, int index) {
            return index < parts.length ? Integer.parseInt(parts[index]) : 0;
        }

        private static String releasePart(String suffix) {
            int dash = suffix.indexOf('-');
            return dash < 0 ? suffix : suffix.substring(0, dash);
        }

        private static String preReleasePart(String suffix) {
            int dash = suffix.indexOf('-');
            return dash < 0 ? "" : suffix.substring(dash + 1);
        }
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
