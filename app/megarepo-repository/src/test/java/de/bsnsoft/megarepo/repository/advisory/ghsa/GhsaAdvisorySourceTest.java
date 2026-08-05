package de.bsnsoft.megarepo.repository.advisory.ghsa;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.bsnsoft.megarepo.repository.advisory.AdvisorySyncException;
import de.bsnsoft.megarepo.repository.advisory.AdvisorySyncResult;
import de.bsnsoft.megarepo.repository.advisory.NormalizedAdvisory;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end behaviour of the source against a stubbed HTTP client — no network, ever.
 */
class GhsaAdvisorySourceTest {

    private static final String BASE = "https://api.github.test/advisories";
    /** Realistic length: the client only scrubs credential-length strings. */
    private static final String TOKEN = "ghp_0123456789abcdefghijklmnopqrstuvwxyz";
    private static final String NEXT_TOKEN = "Y3Vyc29yOnYyOpK5MjAyMg==";
    private static final Map<String, String> NEXT_PAGE_LINK = Map.of(
            "Link",
            "<https://api.github.test/advisories?per_page=100&after=Y3Vyc29yOnYyOpK5MjAyMg%3D%3D>; rel=\"next\"");

    private final StubHttpClient http = new StubHttpClient();

    @Test
    void sourceIdIsStable() {
        assertEquals("GHSA", source(properties(TOKEN, 5)).sourceId());
        assertEquals("GHSA", GhsaAdvisorySource.SOURCE_ID);
    }

    @Test
    void withoutATokenTheSourceDisablesItselfWithoutTouchingTheNetwork() throws Exception {
        AdvisorySyncResult result = source(properties(null, 5)).sync(null);

        assertEquals(0, http.requests().size(), "a token-less deployment must make no request");
        assertTrue(result.advisories().isEmpty());
        assertTrue(result.complete());
        assertEquals("disabled:no-token", result.nextCursor());
    }

    @Test
    void aDisabledCursorStillResumesCleanlyOnceATokenAppears() throws Exception {
        http.respond(200, fixture("/ghsa/page2.json"));

        AdvisorySyncResult result = source(properties(TOKEN, 5)).sync("disabled:no-token");

        assertEquals(1, result.advisories().size());
        assertTrue(result.complete());
        assertFalse(http.lastRequest().uri().toString().contains("after="));
    }

    @Test
    void theKillSwitchDisablesTheSourceToo() throws Exception {
        GhsaProperties off = new GhsaProperties(
                false, TOKEN, BASE, 100, 5, "reviewed", Duration.ofSeconds(30), 0);

        AdvisorySyncResult result = source(off).sync(null);

        assertEquals(0, http.requests().size());
        assertEquals("disabled:switched-off", result.nextCursor());
        assertTrue(result.complete());
    }

    @Test
    void brokenEntriesAreSkippedInsteadOfFailingTheSync() throws Exception {
        http.respond(200, fixture("/ghsa/page1.json"));

        AdvisorySyncResult result = source(properties(TOKEN, 5)).sync(null);

        // The fixture's eight entries include one without a ghsa_id, one with a Maven name
        // missing its groupId, one that is Go-only and one with a malformed vulnerabilities
        // field. The four good ones survive.
        assertEquals(4, result.advisories().size());
        assertTrue(result.complete());
    }

    @Test
    void paginationRunsAcrossSeveralBatches() throws Exception {
        http.respond(200, fixture("/ghsa/page1.json"), NEXT_PAGE_LINK)
                .respond(200, fixture("/ghsa/page2.json"));

        GhsaAdvisorySource source = source(properties(TOKEN, 1));

        AdvisorySyncResult first = source.sync(null);
        assertEquals(4, first.advisories().size());
        assertFalse(first.complete(), "one page per run, and GitHub offered a next page");

        AdvisorySyncResult second = source.sync(first.nextCursor());
        assertEquals(1, second.advisories().size());
        assertTrue(second.complete());

        assertEquals(2, http.requests().size());
        assertTrue(
                http.requests().get(1).uri().toString().contains("after=Y3Vyc29yOnYyOpK5MjAyMg%3D%3D"),
                http.requests().get(1).uri().toString());

        // Once complete, the cursor is the newest updated_at seen — the next run asks for
        // the delta instead of walking every page again.
        GhsaCursor cursor = GhsaCursor.parse(second.nextCursor());
        assertEquals(Instant.parse("2024-06-30T12:00:00Z"), cursor.since());
        assertFalse(cursor.midImport());
    }

    @Test
    void severalPagesPerRunAreConcatenatedIntoOneBatch() throws Exception {
        http.respond(200, fixture("/ghsa/page1.json"), NEXT_PAGE_LINK)
                .respond(200, fixture("/ghsa/page2.json"));

        AdvisorySyncResult result = source(properties(TOKEN, 5)).sync(null);

        assertEquals(5, result.advisories().size());
        assertTrue(result.complete());
        assertEquals(2, http.requests().size());
    }

    @Test
    void aCompletedRunResumesFromTheWatermark() throws Exception {
        http.respond(200, "[]");

        source(properties(TOKEN, 5)).sync(new GhsaCursor(Instant.parse("2024-06-30T12:00:00Z"), null).text());

        assertTrue(
                http.lastRequest().uri().toString().contains("updated=%3E%3D2024-06-30T12%3A00%3A00Z"),
                http.lastRequest().uri().toString());
    }

    @Test
    void aRateLimitKeepsWhatWasFetchedAndReturnsAResumableCursor() throws Exception {
        http.respond(200, fixture("/ghsa/page1.json"), NEXT_PAGE_LINK)
                .respond(
                        403,
                        "{\"message\":\"API rate limit exceeded\"}",
                        Map.of("X-RateLimit-Remaining", "0"));

        AdvisorySyncResult result = source(properties(TOKEN, 5)).sync(null);

        assertEquals(4, result.advisories().size(), "the page fetched before the limit is kept");
        assertFalse(result.complete(), "a rate limit is a pause, not an end");

        GhsaCursor cursor = GhsaCursor.parse(result.nextCursor());
        assertEquals(NEXT_TOKEN, cursor.after(), "resumes at the page that was refused");
        assertTrue(cursor.midImport());
    }

    @Test
    void aRateLimitOnTheVeryFirstRequestIsNotAFailure() throws Exception {
        http.respond(429, "{\"message\":\"secondary rate limit\"}", Map.of("Retry-After", "60"));

        AdvisorySyncResult result = source(properties(TOKEN, 5)).sync(null);

        assertTrue(result.advisories().isEmpty());
        assertFalse(result.complete());
        assertEquals(GhsaCursor.start(), GhsaCursor.parse(result.nextCursor()));
    }

    @Test
    void anExhaustedBudgetStopsPagingEvenOnASuccessfulResponse() throws Exception {
        // Leaves the rest of the hour's budget to the rest of the deployment.
        http.respond(
                200,
                fixture("/ghsa/page1.json"),
                Map.of(
                        "Link", NEXT_PAGE_LINK.get("Link"),
                        "X-RateLimit-Remaining", "0"));

        AdvisorySyncResult result = source(properties(TOKEN, 5)).sync(null);

        assertEquals(1, http.requests().size(), "the second page was not attempted");
        assertEquals(4, result.advisories().size());
        assertFalse(result.complete());
        assertEquals(NEXT_TOKEN, GhsaCursor.parse(result.nextCursor()).after());
    }

    @Test
    void aRejectedTokenIsAFailureAndNeverLeaksTheToken() {
        http.respond(401, "{\"message\":\"Bad credentials for ghp_secret_token\"}");

        AdvisorySyncException failure = assertThrows(
                AdvisorySyncException.class, () -> source(properties("ghp_secret_token", 5)).sync(null));

        assertTrue(failure.getMessage().contains("401"), failure.getMessage());
        assertFalse(failure.getMessage().contains("ghp_secret_token"), failure.getMessage());
    }

    @Test
    void anUnreachableUpstreamIsAFailure() {
        http.fail(new ConnectException("Connection refused"));

        AdvisorySyncException failure = assertThrows(
                AdvisorySyncException.class, () -> source(properties(TOKEN, 5)).sync(null));

        assertTrue(failure.getMessage().contains("unreachable"), failure.getMessage());
    }

    @Test
    void anUnusablePageIsAFailure() {
        http.respond(200, "{\"message\":\"this is not an array\"}");

        assertThrows(AdvisorySyncException.class, () -> source(properties(TOKEN, 5)).sync(null));
    }

    @Test
    void advisoriesCarryThisSourcesId() throws Exception {
        http.respond(200, fixture("/ghsa/page1.json"));

        AdvisorySyncResult result = source(properties(TOKEN, 5)).sync(null);

        for (NormalizedAdvisory advisory : result.advisories()) {
            assertEquals("GHSA", advisory.source());
            assertTrue(advisory.id().startsWith("GHSA-"), advisory.id());
        }
    }

    private GhsaAdvisorySource source(GhsaProperties properties) {
        GhsaApiClient apiClient = new GhsaApiClient(() -> http, properties, "MegaRepo/test");
        return new GhsaAdvisorySource(apiClient, properties, new ObjectMapper());
    }

    private static GhsaProperties properties(String token, int pagesPerSync) {
        return new GhsaProperties(
                true, token, BASE, 100, pagesPerSync, "reviewed", Duration.ofSeconds(30), 0);
    }

    private static String fixture(String path) throws IOException {
        try (InputStream in = GhsaAdvisorySourceTest.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Fixture not found: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
