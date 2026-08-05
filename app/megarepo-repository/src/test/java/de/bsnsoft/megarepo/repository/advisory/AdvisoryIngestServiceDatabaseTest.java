package de.bsnsoft.megarepo.repository.advisory;

import de.bsnsoft.megarepo.database.entity.AdvisoryEntity;
import de.bsnsoft.megarepo.database.entity.AdvisorySyncStateEntity;
import de.bsnsoft.megarepo.database.entity.CveAffectedProductEntity;
import de.bsnsoft.megarepo.database.entity.CveEntryEntity;
import de.bsnsoft.megarepo.database.repository.AdvisoryAffectedJpaRepository;
import de.bsnsoft.megarepo.database.repository.AdvisoryJpaRepository;
import de.bsnsoft.megarepo.database.repository.AdvisorySyncStateJpaRepository;
import de.bsnsoft.megarepo.database.repository.CveAffectedProductJpaRepository;
import de.bsnsoft.megarepo.database.repository.CveEntryJpaRepository;
import de.bsnsoft.megarepo.repository.advisory.nvd.NvdAdvisorySource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The advisory ingest against the real migrated schema.
 *
 * <p>Two properties are load-bearing and neither can be checked without SQL:
 * a repeated sync must not duplicate anything, and one failing source must not
 * stop the others.
 */
class AdvisoryIngestServiceDatabaseTest extends AdvisoryDatabaseTest {

    private static final Instant MODIFIED = Instant.parse("2021-12-14T08:00:00Z");

    @Autowired private AdvisoryIngestService ingestService;
    @Autowired private AdvisoryJpaRepository advisories;
    @Autowired private AdvisoryAffectedJpaRepository affected;
    @Autowired private AdvisorySyncStateJpaRepository syncStates;
    @Autowired private CveEntryJpaRepository cves;
    @Autowired private CveAffectedProductJpaRepository cpes;

    @BeforeEach
    void reset() {
        affected.deleteAllInBatch();
        advisories.deleteAllInBatch();
        syncStates.deleteAllInBatch();
        cpes.deleteAllInBatch();
        cves.deleteAllInBatch();
    }

    @Test
    @DisplayName("syncing the same source twice leaves exactly one advisory and one set of ranges")
    void repeatedSyncIsIdempotent() {
        StubSource source = new StubSource("OSV", batch(log4shell()));

        ingestService.sync(source);
        Instant createdAfterFirstRun = advisories.findById("CVE-2021-44228").orElseThrow().getCreatedAt();

        source.reload(batch(log4shell()));
        ingestService.sync(source);

        assertThat(advisories.count()).isEqualTo(1);
        assertThat(affected.findByAdvisoryId("CVE-2021-44228"))
                .as("advisory_affected has a surrogate key, so ranges must be replaced, not appended")
                .hasSize(2);
        assertThat(advisories.findById("CVE-2021-44228").orElseThrow().getCreatedAt())
                .as("created_at survives a re-sync")
                .isEqualTo(createdAfterFirstRun);
    }

    @Test
    @DisplayName("an upstream advisory that narrows its affected set loses the withdrawn range")
    void resyncRemovesRangesThatUpstreamDropped() {
        StubSource source = new StubSource("OSV", batch(log4shell()));
        ingestService.sync(source);
        assertThat(affected.findByAdvisoryId("CVE-2021-44228")).hasSize(2);

        // Upstream corrects itself: log4j-api was never affected.
        NormalizedAdvisory narrowed = new NormalizedAdvisory(
                "CVE-2021-44228", "OSV", "Log4Shell", "CRITICAL", 10.0, null, MODIFIED, MODIFIED, null,
                List.of(maven("org.apache.logging.log4j", "log4j-core", "2.0", "2.15.0")));
        source.reload(batch(narrowed));
        ingestService.sync(source);

        assertThat(affected.findByAdvisoryId("CVE-2021-44228"))
                .extracting(entity -> entity.getPurlName())
                .containsExactly("log4j-core");
    }

    @Test
    @DisplayName("a completed sync records IDLE, the cursor and last_success_at")
    void successfulSyncUpdatesState() {
        StubSource source = new StubSource(
                "OSV",
                new AdvisorySyncResult(List.of(log4shell()), "page-2", false),
                new AdvisorySyncResult(List.of(), "page-3", true));

        AdvisorySyncSummary summary = ingestService.sync(source);

        assertThat(summary.succeeded()).isTrue();
        assertThat(summary.batches()).isEqualTo(2);
        assertThat(summary.ingested()).isEqualTo(1);

        AdvisorySyncStateEntity state = syncStates.findById("OSV").orElseThrow();
        assertThat(state.getStatus()).isEqualTo("IDLE");
        assertThat(state.getCursor()).isEqualTo("page-3");
        assertThat(state.getLastSuccessAt()).isNotNull();
        assertThat(state.getErrorMessage()).isNull();
    }

    @Test
    @DisplayName("a failing source is recorded and never stops the others")
    void failingSourceDoesNotStopTheRest() {
        StubSource broken = StubSource.failing("GHSA", "rate limited by api.github.com");
        StubSource healthy = new StubSource("OSV", batch(log4shell()));

        List<AdvisorySyncSummary> summaries = ingestService.syncAll(List.of(broken, healthy));

        assertThat(summaries).hasSize(2);
        assertThat(summaries.get(0).succeeded()).isFalse();
        assertThat(summaries.get(0).errorMessage()).contains("rate limited");
        assertThat(summaries.get(1).succeeded()).isTrue();

        AdvisorySyncStateEntity failed = syncStates.findById("GHSA").orElseThrow();
        assertThat(failed.getStatus()).isEqualTo("ERROR");
        assertThat(failed.getErrorMessage()).contains("rate limited");
        assertThat(failed.getLastSuccessAt())
                .as("a failed run must not look like a successful one")
                .isNull();

        assertThat(syncStates.findById("OSV").orElseThrow().getStatus()).isEqualTo("IDLE");
        assertThat(advisories.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("a failed run keeps the cursor, so the next one resumes instead of starting over")
    void failedRunPreservesTheCursor() {
        StubSource source = new StubSource("OSV", new AdvisorySyncResult(List.of(log4shell()), "page-7", false));
        // The first batch persists and stores its cursor, the second one blows up.
        source.failAfterFirstBatch("upstream returned 503");

        AdvisorySyncSummary summary = ingestService.sync(source);

        assertThat(summary.succeeded()).isFalse();
        AdvisorySyncStateEntity state = syncStates.findById("OSV").orElseThrow();
        assertThat(state.getStatus()).isEqualTo("ERROR");
        assertThat(state.getCursor()).isEqualTo("page-7");
        assertThat(advisories.count())
                .as("what was already persisted stays persisted — one transaction per batch")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("NVD never overwrites the purl-shaped version of an advisory OSV already owns")
    void sourcePrecedenceProtectsPurlNativeData() {
        ingestService.sync(new StubSource("OSV", batch(log4shell())));

        NormalizedAdvisory cpeShaped = new NormalizedAdvisory(
                "CVE-2021-44228", "NVD", "vague description", "CRITICAL", 10.0, null, MODIFIED, MODIFIED, null,
                List.of(new NormalizedAffected(
                        CpePurlTranslator.PURL_TYPE, "apache", "log4j",
                        "cpe apache:log4j >=2.0, <2.15.0", "2.0", "2.15.0", null)));

        AdvisorySyncSummary summary = ingestService.sync(new StubSource("NVD", batch(cpeShaped)));

        assertThat(summary.succeeded()).isTrue();
        assertThat(summary.skipped()).isEqualTo(1);

        AdvisoryEntity stored = advisories.findById("CVE-2021-44228").orElseThrow();
        assertThat(stored.getSource()).isEqualTo("OSV");
        assertThat(stored.getSummary()).isEqualTo("Log4Shell");
        assertThat(affected.findByAdvisoryId("CVE-2021-44228"))
                .extracting(entity -> entity.getPurlType())
                .containsOnly("maven");
    }

    @Test
    @DisplayName("a source may always refresh its own advisories")
    void aSourceCanRefreshItsOwnRows() {
        ingestService.sync(new StubSource("NVD", batch(cve("CVE-2024-9999", "NVD", "first description"))));
        ingestService.sync(new StubSource("NVD", batch(cve("CVE-2024-9999", "NVD", "corrected description"))));

        assertThat(advisories.findById("CVE-2024-9999").orElseThrow().getSummary())
                .isEqualTo("corrected description");
    }

    @Test
    @DisplayName("the NVD source reads the local mirror and never downloads anything")
    void nvdSourceIngestsFromTheLocalMirror() {
        givenMirroredCve("CVE-2021-44228", 10.0, "CRITICAL", "Log4Shell");
        givenMirroredCpeMatch("CVE-2021-44228", "apache", "log4j", "2.0", "2.15.0");

        AdvisorySyncSummary summary = ingestService.sync(new NvdAdvisorySource(cves, cpes));

        assertThat(summary.succeeded()).isTrue();
        assertThat(summary.ingested()).isEqualTo(1);

        AdvisoryEntity stored = advisories.findById("CVE-2021-44228").orElseThrow();
        assertThat(stored.getSource()).isEqualTo("NVD");
        assertThat(affected.findByAdvisoryId("CVE-2021-44228"))
                .singleElement()
                .satisfies(range -> {
                    assertThat(range.getPurlType()).isEqualTo("cpe");
                    assertThat(range.getPurlNamespace()).isEqualTo("apache");
                    assertThat(range.getPurlName()).isEqualTo("log4j");
                    assertThat(range.getVersionRange()).isEqualTo("cpe apache:log4j >=2.0, <2.15.0");
                });

        // Re-running is a no-op beyond an update: the cursor resumed past the row.
        AdvisorySyncSummary second = ingestService.sync(new NvdAdvisorySource(cves, cpes));
        assertThat(second.ingested()).isZero();
        assertThat(affected.count()).isEqualTo(1);
    }

    private void givenMirroredCve(String id, double score, String severity, String description) {
        CveEntryEntity entity = new CveEntryEntity();
        entity.setCveId(id);
        entity.setPublished(MODIFIED);
        entity.setLastModified(MODIFIED);
        entity.setCvssScore(score);
        entity.setSeverity(severity);
        entity.setDescription(description);
        cves.saveAndFlush(entity);
    }

    private void givenMirroredCpeMatch(
            String cveId, String vendor, String product, String startIncluding, String endExcluding) {
        CveAffectedProductEntity entity = new CveAffectedProductEntity();
        entity.setCveId(cveId);
        entity.setVendor(vendor);
        entity.setProduct(product);
        entity.setVersionStartIncluding(startIncluding);
        entity.setVersionEndExcluding(endExcluding);
        cpes.saveAndFlush(entity);
    }

    private static AdvisorySyncResult batch(NormalizedAdvisory... advisories) {
        return new AdvisorySyncResult(List.of(advisories), "cursor-1", true);
    }

    private static NormalizedAdvisory log4shell() {
        return new NormalizedAdvisory(
                "CVE-2021-44228", "OSV", "Log4Shell", "CRITICAL", 10.0,
                "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:C/C:H/I:H/A:H", MODIFIED, MODIFIED, null,
                List.of(
                        maven("org.apache.logging.log4j", "log4j-core", "2.0", "2.15.0"),
                        maven("org.apache.logging.log4j", "log4j-api", "2.0", "2.15.0")));
    }

    private static NormalizedAdvisory cve(String id, String source, String summary) {
        return new NormalizedAdvisory(
                id, source, summary, "HIGH", 7.5, null, MODIFIED, MODIFIED, null, List.of());
    }

    private static NormalizedAffected maven(
            String namespace, String name, String introduced, String fixed) {
        return new NormalizedAffected(
                "maven", namespace, name, ">=" + introduced + ", <" + fixed, introduced, fixed, null);
    }

    /** An {@link AdvisorySource} whose batches and failures the test dictates. */
    private static final class StubSource implements AdvisorySource {

        private final String sourceId;
        private final Deque<AdvisorySyncResult> batches = new ArrayDeque<>();
        private String failureMessage;
        private boolean failImmediately;

        StubSource(String sourceId, AdvisorySyncResult... batches) {
            this.sourceId = sourceId;
            reload(batches);
        }

        static StubSource failing(String sourceId, String message) {
            StubSource source = new StubSource(sourceId);
            source.failureMessage = message;
            source.failImmediately = true;
            return source;
        }

        void reload(AdvisorySyncResult... results) {
            batches.clear();
            batches.addAll(List.of(results));
        }

        void failAfterFirstBatch(String message) {
            this.failureMessage = message;
            this.failImmediately = false;
        }

        @Override
        public String sourceId() {
            return sourceId;
        }

        @Override
        public AdvisorySyncResult sync(String cursor) throws AdvisorySyncException {
            if (failImmediately) {
                throw new AdvisorySyncException(failureMessage);
            }
            AdvisorySyncResult next = Optional.ofNullable(batches.poll())
                    .orElse(new AdvisorySyncResult(List.of(), cursor, true));
            if (failureMessage != null && batches.isEmpty() && !next.complete()) {
                // The batch the test wanted persisted has just been handed out;
                // the following call is the one that fails.
                failImmediately = true;
            }
            return next;
        }
    }
}
