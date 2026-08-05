package de.bsnsoft.megarepo.repository.advisory.ghsa;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.bsnsoft.megarepo.repository.advisory.AdvisorySource;
import de.bsnsoft.megarepo.repository.advisory.AdvisorySyncException;
import de.bsnsoft.megarepo.repository.advisory.AdvisorySyncResult;
import de.bsnsoft.megarepo.repository.advisory.NormalizedAdvisory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link AdvisorySource} for GitHub's advisory database (GHSA).
 *
 * <p>GHSA is the best-shaped of the three feeds MegaRepo ingests: entries are curated by
 * hand, name their affected packages in the ecosystem's own coordinates rather than in
 * CPE, and cover exactly the four formats MegaRepo hosts.
 *
 * <h2>Without a token</h2>
 *
 * <p>The advisory database is public, so the REST endpoint answers anonymously — but at
 * 60 requests per hour per IP, shared with everything else on that address. A full import
 * is 200+ requests, so anonymous operation would spend every run rate-limited, days from
 * finishing, and would poison the same budget for anything else behind that IP.
 *
 * <p>So a deployment without {@code megarepo.firewall.ghsa.token} disables this source:
 * {@link #sync(String)} returns an empty, complete result whose cursor is
 * {@link GhsaCursor#DISABLED_NO_TOKEN}, which the caller persists into
 * {@code advisory_sync_state.cursor}. An operator wondering why GHSA contributes nothing
 * sees the reason in the row instead of an empty one that could equally mean "never ran".
 * No HTTP request is made, nothing throws, NVD and OSV are unaffected, and the WARN is
 * logged once per process rather than on every run. Configuring a token later makes the
 * next run start a clean full import — the disabled marker parses as "from the
 * beginning".
 *
 * <h2>Rate limits</h2>
 *
 * <p>Running out of budget is a normal operating state, not a failure: the run stops,
 * keeps everything fetched so far, and returns {@code complete=false} with a cursor that
 * resumes at the exact page it stopped on. No sleeping until the reset — a background job
 * that blocks for an hour is one nobody can stop.
 *
 * <h2>Cursor</h2>
 *
 * <p>See {@link GhsaCursor}. In short: while an import is walking pages the cursor holds
 * GitHub's pagination token plus the frozen window start; once it completes, the cursor
 * holds only the newest {@code updated_at} seen, so the following run asks for the delta.
 */
@Component
public class GhsaAdvisorySource implements AdvisorySource {

    /** Value written to {@code advisory.source} and keying {@code advisory_sync_state}. */
    public static final String SOURCE_ID = "GHSA";

    private static final Logger log = LoggerFactory.getLogger(GhsaAdvisorySource.class);

    private final GhsaApiClient apiClient;
    private final GhsaProperties properties;
    private final GhsaAdvisoryParser parser;

    /** Keeps the "no token" WARN to once per process instead of once per sync run. */
    private final AtomicBoolean disabledWarningLogged = new AtomicBoolean();

    public GhsaAdvisorySource(
            GhsaApiClient apiClient, GhsaProperties properties, ObjectMapper objectMapper) {
        this.apiClient = apiClient;
        this.properties = properties;
        this.parser = new GhsaAdvisoryParser(objectMapper, SOURCE_ID);
    }

    @Override
    public String sourceId() {
        return SOURCE_ID;
    }

    @Override
    public AdvisorySyncResult sync(String cursor) throws AdvisorySyncException {
        if (!properties.enabled()) {
            log.debug("GHSA advisory source is switched off (megarepo.firewall.ghsa.enabled=false)");
            return disabled(GhsaCursor.DISABLED_OFF);
        }
        if (!properties.hasToken()) {
            if (disabledWarningLogged.compareAndSet(false, true)) {
                log.warn("GHSA advisory source disabled: no megarepo.firewall.ghsa.token configured. "
                        + "GitHub's anonymous limit of 60 requests/hour cannot carry an advisory "
                        + "import, so no request is made. Other advisory sources are unaffected.");
            }
            return disabled(GhsaCursor.DISABLED_NO_TOKEN);
        }

        GhsaCursor start = GhsaCursor.parse(cursor);
        List<NormalizedAdvisory> collected = new ArrayList<>();
        GhsaAdvisoryParser.Stats stats = new GhsaAdvisoryParser.Stats();

        String after = start.after();
        Instant watermark = start.since();
        boolean complete = false;
        int pages = 0;

        while (pages < properties.pagesPerSync()) {
            GhsaApiClient.Page page = fetch(after, start.since());
            pages++;

            if (page.rateLimited()) {
                log.info("GHSA rate limit reached after {} page(s), resuming later: {}",
                        pages - 1, stats);
                return new AdvisorySyncResult(
                        collected, new GhsaCursor(start.since(), after).text(), false);
            }
            if (!page.ok()) {
                throw new AdvisorySyncException(describe(page));
            }

            List<NormalizedAdvisory> batch;
            try {
                batch = parser.parsePage(page.body(), stats);
            } catch (IOException e) {
                throw new AdvisorySyncException(
                        "GHSA returned an unusable advisory page: " + e.getMessage(), e);
            }
            collected.addAll(batch);
            watermark = newest(watermark, batch);

            after = page.nextAfter();
            if (after == null) {
                complete = true;
                break;
            }
            if (page.rateLimitRemaining() != null
                    && page.rateLimitRemaining() <= properties.rateLimitReserve()) {
                log.info("GHSA rate limit budget exhausted ({} left); pausing with a resumable cursor",
                        page.rateLimitRemaining());
                break;
            }
        }

        log.info("GHSA sync fetched {} page(s): {}", pages, stats);

        // On completion the pagination token is dropped and only the watermark survives:
        // the next run asks for "everything updated since", which is a handful of pages
        // instead of two hundred. While still paging, the window start stays frozen so
        // GitHub's ascending ordering — and therefore the cursor — remains stable.
        GhsaCursor next = complete
                ? new GhsaCursor(watermark, null)
                : new GhsaCursor(start.since(), after);
        return new AdvisorySyncResult(collected, next.text(), complete);
    }

    private GhsaApiClient.Page fetch(String after, Instant since) throws AdvisorySyncException {
        try {
            return apiClient.fetchPage(after, since);
        } catch (IOException e) {
            throw new AdvisorySyncException("GHSA is unreachable: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AdvisorySyncException("GHSA sync interrupted", e);
        }
    }

    /**
     * The newest {@code modified} timestamp in a batch. GitHub sorts ascending by update
     * time, so this is the point a later run can safely resume from.
     */
    private static Instant newest(Instant current, List<NormalizedAdvisory> batch) {
        Instant newest = current;
        for (NormalizedAdvisory advisory : batch) {
            Instant modified = advisory.modified();
            if (modified != null && (newest == null || modified.isAfter(newest))) {
                newest = modified;
            }
        }
        return newest;
    }

    private static AdvisorySyncResult disabled(String marker) {
        return new AdvisorySyncResult(List.of(), marker, true);
    }

    /**
     * Describes a rejected request without ever quoting the token. 401 and a
     * non-rate-limited 403 both mean the credential is the problem, which is worth saying
     * plainly — it is the one GHSA failure an operator can actually fix.
     */
    private static String describe(GhsaApiClient.Page page) {
        String detail = switch (page.statusCode()) {
            case 401 -> "the configured token was rejected";
            case 403 -> "access was denied — check that the token is valid and, on an "
                    + "SSO-protected organisation, authorised";
            case 404 -> "the advisories endpoint was not found — check "
                    + "megarepo.firewall.ghsa.base-url";
            default -> "unexpected response";
        };
        return "GHSA request failed (HTTP %d): %s. Body: %s"
                .formatted(page.statusCode(), detail, truncate(page.body(), 300));
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
