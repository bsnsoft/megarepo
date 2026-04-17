package de.bsnsoft.megarepo.repository.nvd;

import de.bsnsoft.megarepo.database.entity.NvdFirewallSettingsEntity;
import de.bsnsoft.megarepo.database.entity.NvdSyncStateEntity;
import de.bsnsoft.megarepo.database.repository.NvdFirewallSettingsJpaRepository;
import de.bsnsoft.megarepo.database.repository.NvdSyncStateJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Orchestrates syncing NVD CVE data into the local mirror. Runs async on a
 * virtual thread. Writes progress to the nvd_sync_state row via
 * {@link NvdIngestService} so the UI can poll and show a progress bar.
 *
 * Modes:
 *   - FULL: fetches every CVE (startIndex paginates through totalResults)
 *   - DELTA: fetches CVEs modified since last_success_at (NVD's
 *     lastModStartDate/lastModEndDate window, max 120 days)
 */
@Service
public class NvdSyncService {

    private static final Logger log = LoggerFactory.getLogger(NvdSyncService.class);
    private static final int SETTINGS_ID = 1;
    private static final int STATE_ID = 1;
    private static final Duration DELTA_MAX_WINDOW = Duration.ofDays(120);

    private final NvdApiClient apiClient;
    private final NvdIngestService ingest;
    private final NvdSyncStateJpaRepository stateRepo;
    private final NvdFirewallSettingsJpaRepository settingsRepo;
    private final long rateLimitDelayMs;

    private final AtomicBoolean syncing = new AtomicBoolean(false);

    public NvdSyncService(
            NvdApiClient apiClient,
            NvdIngestService ingest,
            NvdSyncStateJpaRepository stateRepo,
            NvdFirewallSettingsJpaRepository settingsRepo,
            @Value("${megarepo.nvd.rate-limit-delay-ms:700}") long rateLimitDelayMs) {
        this.apiClient = apiClient;
        this.ingest = ingest;
        this.stateRepo = stateRepo;
        this.settingsRepo = settingsRepo;
        this.rateLimitDelayMs = rateLimitDelayMs;
    }

    public NvdSyncStateEntity getState() {
        return stateRepo.findById(STATE_ID).orElseGet(() -> {
            var s = new NvdSyncStateEntity();
            return stateRepo.save(s);
        });
    }

    public boolean triggerFullSync() {
        return trigger("FULL", null);
    }

    public boolean triggerDeltaSync() {
        Instant since = getState().getLastSuccessAt();
        if (since == null) {
            log.info("No prior successful sync; promoting delta to full sync");
            return triggerFullSync();
        }
        return trigger("DELTA", since);
    }

    private boolean trigger(String mode, Instant since) {
        if (!syncing.compareAndSet(false, true)) {
            log.warn("Sync already running; refusing to start {} sync", mode);
            return false;
        }
        NvdFirewallSettingsEntity settings = settingsRepo.findById(SETTINGS_ID)
                .orElseGet(NvdFirewallSettingsEntity::new);
        String apiKey = settings.getApiKey();

        ingest.markStart(mode);
        Thread.ofVirtual().name("nvd-sync-" + mode.toLowerCase()).start(() -> {
            try {
                if ("DELTA".equals(mode)) {
                    runDelta(since, apiKey);
                } else {
                    runFull(apiKey);
                }
                ingest.markSuccess();
            } catch (Exception e) {
                log.error("NVD sync failed", e);
                ingest.markError(e.getMessage());
            } finally {
                syncing.set(false);
            }
        });
        return true;
    }

    private void runFull(String apiKey) throws IOException, InterruptedException {
        int startIndex = 0;
        int totalResults = Integer.MAX_VALUE;
        int processed = 0;

        while (startIndex < totalResults) {
            var page = apiClient.fetchPage(startIndex, NvdApiClient.DEFAULT_PAGE_SIZE, null, null, apiKey);
            totalResults = page.totalResults();
            processed += ingest.ingestPage(page.cves());
            startIndex += page.resultsPerPage() > 0 ? page.resultsPerPage() : NvdApiClient.DEFAULT_PAGE_SIZE;
            ingest.updateProgress(totalResults, processed);
            log.info("NVD full sync: {} / {} CVEs", processed, totalResults);
            if (startIndex < totalResults) {
                Thread.sleep(rateLimitDelayMs);
            }
        }
    }

    private void runDelta(Instant since, String apiKey) throws IOException, InterruptedException {
        Instant end = Instant.now();
        Instant start = since;
        int processed = 0;

        while (start.isBefore(end)) {
            Instant windowEnd = start.plus(DELTA_MAX_WINDOW);
            if (windowEnd.isAfter(end)) windowEnd = end;

            int startIndex = 0;
            int totalResults = Integer.MAX_VALUE;
            while (startIndex < totalResults) {
                var page = apiClient.fetchPage(startIndex, NvdApiClient.DEFAULT_PAGE_SIZE, start, windowEnd, apiKey);
                totalResults = page.totalResults();
                processed += ingest.ingestPage(page.cves());
                startIndex += page.resultsPerPage() > 0 ? page.resultsPerPage() : NvdApiClient.DEFAULT_PAGE_SIZE;
                ingest.updateProgress(null, processed);
                log.info("NVD delta sync window {}..{}: {} / {} CVEs",
                        start, windowEnd, Math.min(startIndex, totalResults), totalResults);
                if (startIndex < totalResults) {
                    Thread.sleep(rateLimitDelayMs);
                }
            }
            start = windowEnd;
        }
    }
}
