package de.bsnsoft.megarepo.repository.advisory.nvd;

import de.bsnsoft.megarepo.database.entity.CveAffectedProductEntity;
import de.bsnsoft.megarepo.database.entity.CveEntryEntity;
import de.bsnsoft.megarepo.database.repository.CveAffectedProductJpaRepository;
import de.bsnsoft.megarepo.database.repository.CveEntryJpaRepository;
import de.bsnsoft.megarepo.repository.advisory.AdvisorySyncException;
import de.bsnsoft.megarepo.repository.advisory.AdvisorySyncResult;
import de.bsnsoft.megarepo.repository.advisory.NormalizedAdvisory;
import de.bsnsoft.megarepo.repository.advisory.NormalizedAffected;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link NvdAdvisorySource} against a mocked NVD mirror.
 *
 * <p>Nothing here reaches the network — which is also the property under test:
 * this source exists precisely so that no second NVD download appears in the
 * codebase.
 */
class NvdAdvisorySourceTest {

    private static final Instant T1 = Instant.parse("2021-12-10T10:15:30Z");
    private static final Instant T2 = Instant.parse("2021-12-14T08:00:00Z");

    private final CveEntryJpaRepository cves = mock(CveEntryJpaRepository.class);
    private final CveAffectedProductJpaRepository cpes = mock(CveAffectedProductJpaRepository.class);

    @Test
    @DisplayName("normalises a CVE and its CPE matches into a purl-shaped advisory")
    void normalisesCveWithCpeMatches() throws Exception {
        givenCves(cve("CVE-2021-44228", T1, 10.0, "CRITICAL", "Log4Shell"));
        givenCpeMatches(cpeMatch("CVE-2021-44228", "apache", "log4j", "2.0", "2.15.0"));

        AdvisorySyncResult result = source(10).sync(null);

        assertThat(result.advisories()).hasSize(1);
        NormalizedAdvisory advisory = result.advisories().get(0);
        assertThat(advisory.id()).isEqualTo("CVE-2021-44228");
        assertThat(advisory.source()).isEqualTo("NVD");
        assertThat(advisory.summary()).isEqualTo("Log4Shell");
        assertThat(advisory.severity()).isEqualTo("CRITICAL");
        assertThat(advisory.cvssScore()).isEqualTo(10.0);
        assertThat(advisory.published()).isEqualTo(T1);
        assertThat(advisory.modified()).isEqualTo(T1);

        assertThat(advisory.affected()).hasSize(1);
        NormalizedAffected affected = advisory.affected().get(0);
        assertThat(affected.purlType()).isEqualTo("cpe");
        assertThat(affected.purlNamespace()).isEqualTo("apache");
        assertThat(affected.purlName()).isEqualTo("log4j");
        assertThat(affected.introduced()).isEqualTo("2.0");
        assertThat(affected.fixed()).isEqualTo("2.15.0");
    }

    @Test
    @DisplayName("the mirror keeps no CVSS vector, so none is invented")
    void noCvssVectorIsInvented() throws Exception {
        givenCves(cve("CVE-2021-44228", T1, 10.0, "CRITICAL", "Log4Shell"));
        givenNoCpeMatches();

        assertThat(source(10).sync(null).advisories().get(0).cvssVector()).isNull();
    }

    @Test
    @DisplayName("a mirror score of 0.0 is reported as absent, not as a genuine 0.0")
    void defaultedZeroScoreBecomesNull() throws Exception {
        // cve_entries.cvss_score is NOT NULL DEFAULT 0, so "unscored" and
        // "scored 0.0" are the same row. advisory.cvss_score is nullable exactly
        // to avoid presenting the former as the latter.
        givenCves(cve("CVE-2024-0001", T1, 0.0, null, "not yet analysed"));
        givenNoCpeMatches();

        assertThat(source(10).sync(null).advisories().get(0).cvssScore()).isNull();
    }

    @Test
    @DisplayName("a CVE with no CPE matches still becomes an advisory, just with no ranges")
    void cveWithoutCpeMatches() throws Exception {
        givenCves(cve("CVE-2024-0002", T1, 7.5, "HIGH", "hardware flaw"));
        givenNoCpeMatches();

        NormalizedAdvisory advisory = source(10).sync(null).advisories().get(0);
        assertThat(advisory.affected()).isEmpty();
    }

    @Test
    @DisplayName("duplicate CPE configurations collapse into one affected range")
    void duplicateCpeMatchesCollapse() throws Exception {
        // NVD repeats the same vendor/product/range once per configuration node
        // (one per OS or platform combination). The platform distinction does
        // not survive the translation, so keeping the duplicates would only
        // multiply advisory_affected rows.
        givenCves(cve("CVE-2021-44228", T1, 10.0, "CRITICAL", "Log4Shell"));
        givenCpeMatches(
                cpeMatch("CVE-2021-44228", "apache", "log4j", "2.0", "2.15.0"),
                cpeMatch("CVE-2021-44228", "apache", "log4j", "2.0", "2.15.0"),
                cpeMatch("CVE-2021-44228", "apache", "log4j", "2.0", "2.16.0"));

        assertThat(source(10).sync(null).advisories().get(0).affected()).hasSize(2);
    }

    @Test
    @DisplayName("the cursor is a keyset over (last_modified, cve_id) and resumes where it stopped")
    void cursorIsAKeysetOverLastModifiedAndId() throws Exception {
        givenCves(
                cve("CVE-2021-44228", T1, 10.0, "CRITICAL", "Log4Shell"),
                cve("CVE-2021-45046", T2, 9.0, "CRITICAL", "follow-up"));
        givenNoCpeMatches();

        AdvisorySyncResult first = source(2).sync(null);

        assertThat(first.nextCursor()).isEqualTo(T2.toEpochMilli() + "|CVE-2021-45046");
        assertThat(first.complete())
                .as("a full page means there may be more")
                .isFalse();

        NvdAdvisorySource.Cursor resumed = NvdAdvisorySource.Cursor.parse(first.nextCursor());
        assertThat(resumed.lastModified()).isEqualTo(T2);
        assertThat(resumed.cveId()).isEqualTo("CVE-2021-45046");
    }

    @Test
    @DisplayName("a short page means the source is complete")
    void shortPageCompletesTheSync() throws Exception {
        givenCves(cve("CVE-2021-44228", T1, 10.0, "CRITICAL", "Log4Shell"));
        givenNoCpeMatches();

        assertThat(source(10).sync(null).complete()).isTrue();
    }

    @Test
    @DisplayName("an exhausted mirror returns the empty, finished result")
    void emptyMirrorReturnsEmptyResult() throws Exception {
        when(cves.findModifiedAfter(any(), anyString(), any(Pageable.class))).thenReturn(List.of());

        AdvisorySyncResult result = source(10).sync("0|");

        assertThat(result.advisories()).isEmpty();
        assertThat(result.complete()).isTrue();
    }

    @Test
    @DisplayName("a null cursor starts at the beginning of time")
    void nullCursorStartsFromTheBeginning() {
        NvdAdvisorySource.Cursor cursor = NvdAdvisorySource.Cursor.parse(null);

        assertThat(cursor.lastModified()).isEqualTo(Instant.EPOCH);
        assertThat(cursor.cveId()).isEmpty();
    }

    @Test
    @DisplayName("a corrupted cursor restarts rather than wedging the source")
    void corruptCursorRestarts() {
        // The ingest is idempotent, so a full re-read is only slower. A
        // permanent error state would need an operator to clear it.
        assertThat(NvdAdvisorySource.Cursor.parse("garbage").lastModified()).isEqualTo(Instant.EPOCH);
        assertThat(NvdAdvisorySource.Cursor.parse("not-a-number|CVE-1").lastModified())
                .isEqualTo(Instant.EPOCH);
    }

    @Test
    @DisplayName("an unreadable mirror is reported as a sync failure, not as an empty result")
    void databaseFailureBecomesSyncException() {
        when(cves.findModifiedAfter(any(), anyString(), any(Pageable.class)))
                .thenThrow(new QueryTimeoutException("statement timeout"));

        assertThatThrownBy(() -> source(10).sync(null))
                .isInstanceOf(AdvisorySyncException.class)
                .hasMessageContaining("Local NVD mirror is not readable");
    }

    private NvdAdvisorySource source(int batchSize) {
        return new NvdAdvisorySource(cves, cpes, batchSize);
    }

    private void givenCves(CveEntryEntity... entries) {
        when(cves.findModifiedAfter(any(), anyString(), any(Pageable.class)))
                .thenReturn(List.of(entries));
    }

    private void givenCpeMatches(CveAffectedProductEntity... matches) {
        when(cpes.findByCveIdIn(anyCollection())).thenReturn(List.of(matches));
    }

    private void givenNoCpeMatches() {
        when(cpes.findByCveIdIn(anyCollection())).thenReturn(List.of());
    }

    private static CveEntryEntity cve(
            String id, Instant modified, double score, String severity, String description) {
        CveEntryEntity entity = new CveEntryEntity();
        entity.setCveId(id);
        entity.setPublished(modified);
        entity.setLastModified(modified);
        entity.setCvssScore(score);
        entity.setSeverity(severity);
        entity.setDescription(description);
        return entity;
    }

    private static CveAffectedProductEntity cpeMatch(
            String cveId, String vendor, String product, String startIncluding, String endExcluding) {
        CveAffectedProductEntity entity = new CveAffectedProductEntity();
        entity.setCveId(cveId);
        entity.setVendor(vendor);
        entity.setProduct(product);
        entity.setVersionStartIncluding(startIncluding);
        entity.setVersionEndExcluding(endExcluding);
        return entity;
    }
}
