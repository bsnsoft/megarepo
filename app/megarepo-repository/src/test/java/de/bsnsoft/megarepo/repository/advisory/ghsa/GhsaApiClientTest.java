package de.bsnsoft.megarepo.repository.advisory.ghsa;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Request building, {@code Link}-header pagination and rate-limit recognition. */
class GhsaApiClientTest {

    private static final String BASE = "https://api.github.test/advisories";
    private static final String TOKEN = "ghp_0123456789abcdefghijklmnopqrstuvwxyz";

    private final StubHttpClient http = new StubHttpClient();

    @Test
    void firstRequestAsksForAscendingUpdateOrder() {
        URI uri = client(properties(null)).buildUri(null, null);

        assertEquals(BASE, uri.toString().substring(0, BASE.length()));
        String query = uri.getQuery();
        assertTrue(query.contains("per_page=100"), query);
        // Ascending order by update time is what makes the import resumable.
        assertTrue(query.contains("sort=updated"), query);
        assertTrue(query.contains("direction=asc"), query);
        assertTrue(query.contains("type=reviewed"), query);
        assertFalse(query.contains("after="), query);
        assertFalse(query.contains("updated="), query);
    }

    @Test
    void deltaRequestFiltersOnTheWatermark() {
        URI uri = client(properties(null)).buildUri(null, Instant.parse("2024-01-01T00:00:00Z"));

        assertTrue(
                uri.toString().contains("updated=%3E%3D2024-01-01T00%3A00%3A00Z"),
                uri.toString());
        assertEquals(">=2024-01-01T00:00:00Z", queryParam(uri, "updated"));
    }

    @Test
    void resumeRequestCarriesThePaginationToken() {
        URI uri = client(properties(null)).buildUri("Y3Vyc29yOnYyOpK5MjAyMg==", null);

        assertEquals("Y3Vyc29yOnYyOpK5MjAyMg==", queryParam(uri, "after"));
        assertTrue(uri.toString().contains("after=Y3Vyc29yOnYyOpK5MjAyMg%3D%3D"), uri.toString());
    }

    @Test
    void pageSizeIsClampedToGitHubsMaximum() {
        GhsaProperties properties = new GhsaProperties(
                true, TOKEN, BASE, 5000, 5, "reviewed", Duration.ofSeconds(30), 0);

        assertEquals(100, properties.pageSize());
        assertTrue(client(properties).buildUri(null, null).getQuery().contains("per_page=100"));
    }

    @Test
    void tokenTravelsAsABearerHeaderAndNeverInTheUrl() throws Exception {
        http.respond(200, "[]");

        client(properties("ghp_secret_token")).fetchPage(null, null);

        var request = http.lastRequest();
        assertEquals(
                Optional.of("Bearer ghp_secret_token"),
                request.headers().firstValue("Authorization"));
        assertEquals(
                Optional.of("application/vnd.github+json"), request.headers().firstValue("Accept"));
        assertEquals(
                Optional.of("2022-11-28"), request.headers().firstValue("X-GitHub-Api-Version"));
        assertFalse(request.uri().toString().contains("ghp_secret_token"));
    }

    @Test
    void nextPageTokenComesFromTheLinkHeader() throws Exception {
        http.respond(
                200,
                "[]",
                Map.of(
                        "Link",
                        "<https://api.github.test/advisories?per_page=100&after=Y3Vyc29yOnYyOpK5MjAyMg%3D%3D>; rel=\"next\", "
                                + "<https://api.github.test/advisories?per_page=100&before=Zmlyc3Q%3D>; rel=\"prev\""));

        GhsaApiClient.Page page = client(properties(TOKEN)).fetchPage(null, null);

        assertEquals("Y3Vyc29yOnYyOpK5MjAyMg==", page.nextAfter());
        assertTrue(page.ok());
    }

    @Test
    void aLastPageHasNoNextToken() {
        assertNull(GhsaApiClient.nextAfter(null));
        assertNull(GhsaApiClient.nextAfter(""));
        assertNull(GhsaApiClient.nextAfter("<https://api.github.test/advisories?before=x>; rel=\"prev\""));
        assertNull(GhsaApiClient.nextAfter("<https://api.github.test/advisories>; rel=\"next\""));
    }

    @Test
    void rateLimitHeadersAreRead() throws Exception {
        http.respond(200, "[]", Map.of("X-RateLimit-Remaining", "17"));

        GhsaApiClient.Page page = client(properties(TOKEN)).fetchPage(null, null);

        assertEquals(Long.valueOf(17), page.rateLimitRemaining());
        assertFalse(page.rateLimited());
    }

    @Test
    void exhaustedPrimaryLimitIsRecognised() throws Exception {
        http.respond(
                403,
                "{\"message\":\"API rate limit exceeded\"}",
                Map.of("X-RateLimit-Remaining", "0", "X-RateLimit-Reset", "1717171717"));

        GhsaApiClient.Page page = client(properties(TOKEN)).fetchPage(null, null);

        assertTrue(page.rateLimited());
        assertFalse(page.ok());
    }

    @Test
    void secondaryLimitWithRetryAfterIsRecognised() throws Exception {
        http.respond(
                429,
                "{\"message\":\"You have exceeded a secondary rate limit\"}",
                Map.of("Retry-After", "60"));

        GhsaApiClient.Page page = client(properties(TOKEN)).fetchPage(null, null);

        assertTrue(page.rateLimited());
        assertEquals(Long.valueOf(60), page.retryAfterSeconds());
    }

    @Test
    void aRejectedTokenIsNotARateLimit() throws Exception {
        http.respond(401, "{\"message\":\"Bad credentials\"}");

        GhsaApiClient.Page page = client(properties(TOKEN)).fetchPage(null, null);

        assertFalse(page.rateLimited());
        assertFalse(page.ok());
    }

    @Test
    void aResponseBodyEchoingTheTokenIsScrubbed() throws Exception {
        http.respond(403, "{\"message\":\"proxy rejected Bearer ghp_secret_token\"}");

        GhsaApiClient.Page page = client(properties("ghp_secret_token")).fetchPage(null, null);

        assertFalse(page.body().contains("ghp_secret_token"), page.body());
        assertTrue(page.body().contains("***"), page.body());
    }

    @Test
    void aMisconfiguredShortTokenDoesNotCorruptThePayload() throws Exception {
        // Substituting a one-character "token" would hit every advisory in the page.
        // Only credential-length values are scrubbed.
        http.respond(200, "[{\"ghsa_id\":\"GHSA-test\",\"type\":\"reviewed\"}]");

        GhsaApiClient.Page page = client(properties("t")).fetchPage(null, null);

        assertEquals("[{\"ghsa_id\":\"GHSA-test\",\"type\":\"reviewed\"}]", page.body());
    }

    private GhsaApiClient client(GhsaProperties properties) {
        return new GhsaApiClient(() -> http, properties, "MegaRepo/test");
    }

    private static GhsaProperties properties(String token) {
        return new GhsaProperties(true, token, BASE, 100, 5, "reviewed", Duration.ofSeconds(30), 0);
    }

    private static String queryParam(URI uri, String name) {
        for (String pair : uri.getQuery().split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) {
                return pair.substring(eq + 1);
            }
        }
        return null;
    }
}
