package de.bsnsoft.megarepo.format.nuget.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.bsnsoft.megarepo.core.repository.RepositoryConfig;
import de.bsnsoft.megarepo.repository.proxy.RemoteHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves and caches the upstream NuGet V3 service index for proxy
 * repositories. The configured {@code proxy.remoteUrl} is the upstream
 * service index URL itself (e.g. {@code https://api.nuget.org/v3/index.json});
 * the individual resource base URLs (flat container, registrations, search,
 * search autocomplete) are discovered from it and cached in memory using the
 * repository's metadata TTL.
 *
 * <p>Failed lookups are cached too, with an exponential backoff, so a broken or
 * slow upstream is contacted once per backoff window instead of once per client
 * request.
 */
@Component
public class UpstreamServiceIndexResolver {

    private static final Logger log = LoggerFactory.getLogger(UpstreamServiceIndexResolver.class);
    private static final int DEFAULT_METADATA_TTL_MINUTES = 5;

    /**
     * Backoff after the first failed service-index fetch. Without a negative
     * cache every single client request re-contacts a feed that is broken or
     * slow, so one dead upstream drags down every restore against that
     * repository. The window doubles per consecutive failure (30s → 1m → 2m →
     * 4m) up to {@link #NEGATIVE_CACHE_MAX_BACKOFF} and is dropped on the first
     * success, so a failure never becomes permanent.
     *
     * <p>The base is deliberately far below the {@value #DEFAULT_METADATA_TTL_MINUTES}
     * minute metadata TTL: a feed that recovers quickly is picked up on the next
     * client retry (dotnet restore retries within seconds), while a feed that
     * stays down costs at most one upstream request per window.
     */
    private static final Duration NEGATIVE_CACHE_BASE_BACKOFF = Duration.ofSeconds(30);

    /** Upper bound of the exponential backoff — at worst one retry per 5 minutes. */
    private static final Duration NEGATIVE_CACHE_MAX_BACKOFF = Duration.ofMinutes(5);

    /** The failure counter stops here; the backoff is capped long before. */
    private static final int MAX_COUNTED_FAILURES = 16;

    /** Resource base URLs of an upstream V3 feed, trailing slashes stripped. */
    public record UpstreamResources(
            String flatContainerBase,
            String registrationsBase,
            String searchBase,
            String autocompleteBase) {}

    private record CacheEntry(UpstreamResources resources, Instant fetchedAt) {}

    /**
     * A failed service-index fetch, remembered so the next requests do not run
     * straight back into the same broken upstream.
     *
     * @param failedAt            when the most recent attempt failed
     * @param consecutiveFailures failures in a row; drives the exponential backoff
     */
    private record FailureEntry(Instant failedAt, int consecutiveFailures) {

        Duration backoff() {
            // Shift capped well below 63 so the doubling cannot overflow.
            int shift = Math.max(0, Math.min(consecutiveFailures - 1, MAX_COUNTED_FAILURES));
            Duration backoff = NEGATIVE_CACHE_BASE_BACKOFF.multipliedBy(1L << shift);
            return backoff.compareTo(NEGATIVE_CACHE_MAX_BACKOFF) > 0 ? NEGATIVE_CACHE_MAX_BACKOFF : backoff;
        }

        boolean isBackoffOver(Instant now) {
            return !now.isBefore(failedAt.plus(backoff()));
        }
    }

    private final RemoteHttpClient remoteHttpClient;
    private final Clock clock;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConcurrentHashMap<UUID, CacheEntry> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, FailureEntry> failures = new ConcurrentHashMap<>();

    @Autowired
    public UpstreamServiceIndexResolver(RemoteHttpClient remoteHttpClient) {
        this(remoteHttpClient, Clock.systemUTC());
    }

    /** Test seam: lets tests step over the cache TTL and the failure backoff without sleeping. */
    UpstreamServiceIndexResolver(RemoteHttpClient remoteHttpClient, Clock clock) {
        this.remoteHttpClient = remoteHttpClient;
        this.clock = clock;
    }

    public Optional<UpstreamResources> resolve(RepositoryConfig repo) {
        CacheEntry cached = cache.get(repo.id());
        if (cached != null && !isExpired(cached, repo)) {
            return Optional.of(cached.resources());
        }

        FailureEntry failure = failures.get(repo.id());
        if (failure != null && !failure.isBackoffOver(clock.instant())) {
            // Negative cache hit — the upstream is known broken, do not fetch.
            // A stale resource map is still better than nothing.
            log.debug("Skipping upstream service index fetch for repo={}: {} consecutive failures, "
                            + "backing off {}s since {}",
                    repo.name(), failure.consecutiveFailures(), failure.backoff().toSeconds(), failure.failedAt());
            return cached != null ? Optional.of(cached.resources()) : Optional.empty();
        }

        String indexUrl = remoteIndexUrl(repo);
        if (indexUrl == null) {
            // Misconfiguration, not an upstream failure: no request was made,
            // so there is nothing to back off from.
            log.warn("NuGet proxy repository '{}' has no remoteUrl configured", repo.name());
            return Optional.empty();
        }

        try {
            Optional<UpstreamResources> fetched = fetchServiceIndex(repo, indexUrl);
            if (fetched.isPresent()) {
                cache.put(repo.id(), new CacheEntry(fetched.get(), clock.instant()));
                failures.remove(repo.id());
                return fetched;
            }
            // Reachable but unusable (bad status, or an index without the
            // resources we need) — treat it like a failed fetch.
            recordFailure(repo);
        } catch (IOException e) {
            log.warn("Failed to fetch upstream service index {} for repo={}: {}",
                    indexUrl, repo.name(), e.getMessage());
            recordFailure(repo);
        }
        // Upstream hiccup: keep serving the stale resource map, if we have one.
        return cached != null ? Optional.of(cached.resources()) : Optional.empty();
    }

    /** Drops the cached resource map and any recorded failure (used by tests). */
    public void invalidate(UUID repoId) {
        cache.remove(repoId);
        failures.remove(repoId);
    }

    private void recordFailure(RepositoryConfig repo) {
        FailureEntry entry = failures.compute(repo.id(), (id, previous) -> new FailureEntry(
                clock.instant(),
                // Counting stops once the backoff is capped anyway; keeps the counter finite.
                previous == null ? 1 : Math.min(previous.consecutiveFailures() + 1, MAX_COUNTED_FAILURES)));
        log.debug("Upstream service index for repo={} failed {} time(s) in a row, "
                        + "suppressing further fetches for {}s",
                repo.name(), entry.consecutiveFailures(), entry.backoff().toSeconds());
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
            String id = resource.path("@id").asText("");
            if (id.isEmpty()) {
                continue;
            }
            // One position per entry, not per type: the tie-breaker means
            // "position in the resources array" regardless of how many types
            // the entry declares.
            int position = order++;
            for (String type : typeValues(resource.path("@type"))) {
                ResourceCandidate candidate = ResourceCandidate.parse(type, id, position);
                byBaseType.merge(candidate.baseType(), candidate, ResourceCandidate::preferred);
            }
        }

        String flatContainer = idOf(byBaseType.get("PackageBaseAddress"));
        String registrations = idOf(byBaseType.get("RegistrationsBaseUrl"));
        String search = idOf(byBaseType.get("SearchQueryService"));
        String autocomplete = idOf(byBaseType.get("SearchAutocompleteService"));

        if (flatContainer == null) {
            log.warn("Upstream service index {} has no PackageBaseAddress resource", indexUrl);
            return Optional.empty();
        }

        return Optional.of(new UpstreamResources(
                stripTrailingSlash(flatContainer),
                registrations != null ? stripTrailingSlash(registrations) : null,
                search != null ? stripTrailingSlash(search) : null,
                autocomplete != null ? stripTrailingSlash(autocomplete) : null));
    }

    /**
     * The {@code @type} values of one {@code resources[]} entry.
     *
     * <p>JSON-LD allows {@code @type} to be either a single string or an array
     * of strings, and non-standard feeds (Artifactory, Azure DevOps) do emit the
     * array form:
     * <pre>"@type": ["SearchQueryService", "SearchQueryService/3.5.0"]</pre>
     * Reading such an entry as a plain string yields {@code ""}, which used to
     * drop the entry silently and left the whole feed unusable for no visible
     * reason. Every listed type is honoured instead, so one entry can serve
     * several resource types at once — exactly what those feeds mean by it.
     *
     * <p>The single-string form keeps its previous behaviour verbatim. Anything
     * else (object, array of objects, missing) contributes no type and the entry
     * is skipped, as before.
     */
    private static List<String> typeValues(JsonNode typeNode) {
        if (!typeNode.isArray()) {
            String type = typeNode.asText("");
            return type.isEmpty() ? List.of() : List.of(type);
        }
        List<String> types = new ArrayList<>(typeNode.size());
        for (JsonNode element : typeNode) {
            if (element.isTextual() && !element.asText().isEmpty()) {
                types.add(element.asText());
            }
        }
        return types;
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
        return clock.instant().isAfter(entry.fetchedAt().plus(ttlMinutes, ChronoUnit.MINUTES));
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
