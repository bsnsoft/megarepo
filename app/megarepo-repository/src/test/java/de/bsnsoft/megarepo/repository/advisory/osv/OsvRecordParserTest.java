package de.bsnsoft.megarepo.repository.advisory.osv;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.bsnsoft.megarepo.repository.advisory.NormalizedAdvisory;
import de.bsnsoft.megarepo.repository.advisory.NormalizedAffected;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Normalisation of real OSV record shapes. Fixtures follow the OSV schema as published —
 * including the parts that are inconvenient: severity as a vector rather than a number,
 * {@code introduced: "0"}, {@code MAL-} records with no ranges at all, and advisories that
 * name three ecosystems of which MegaRepo hosts one.
 *
 * <p>No test here touches the network; the parser has no I/O to reach it with.
 */
class OsvRecordParserTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final OsvRecordParser parser = new OsvRecordParser("OSV");

    @Test
    void normalisesAMavenAdvisoryWithSeveralRanges() {
        OsvSyncStats stats = new OsvSyncStats();
        NormalizedAdvisory advisory = parse("maven-log4shell.json", stats).orElseThrow();

        assertEquals("GHSA-jfh8-c2jp-5v3q", advisory.id());
        assertEquals("OSV", advisory.source());
        assertEquals("Remote code injection in Log4j", advisory.summary());
        assertEquals("CRITICAL", advisory.severity());
        assertEquals(10.0, advisory.cvssScore(), 0.001);
        assertEquals("CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:C/C:H/I:H/A:H", advisory.cvssVector());
        assertEquals(Instant.parse("2021-12-10T16:51:04Z"), advisory.published());
        assertEquals(Instant.parse("2026-02-14T05:23:41Z"), advisory.modified());
        assertNull(advisory.withdrawnAt());

        assertEquals(3, advisory.affected().size(), "one row per introduced/fixed interval");
        NormalizedAffected first = advisory.affected().get(0);
        assertEquals("maven", first.purlType());
        assertEquals("org.apache.logging.log4j", first.purlNamespace());
        assertEquals("log4j-core", first.purlName());
        assertEquals("2.0-beta9", first.introduced());
        assertEquals("2.3.1", first.fixed());
        assertNull(first.lastAffected());
        assertEquals("<= 2.14.1", first.versionRange(), "the one range expression OSV publishes");

        assertEquals("2.13.0", advisory.affected().get(2).introduced());
        assertEquals("2.15.0", advisory.affected().get(2).fixed());
    }

    @Test
    void malRecordKeepsNoScoreAndBlocksEveryVersion() {
        OsvSyncStats stats = new OsvSyncStats();
        NormalizedAdvisory advisory = parse("npm-malicious.json", stats).orElseThrow();

        assertEquals("MAL-2024-7391", advisory.id());
        assertNull(advisory.cvssScore(), "a MAL- record has no CVSS — 0.0 would read as harmless");
        assertNull(advisory.cvssVector());
        assertNull(advisory.severity());
        assertNotNull(advisory.summary());

        assertEquals(1, advisory.affected().size());
        NormalizedAffected affected = advisory.affected().get(0);
        assertEquals("npm", affected.purlType());
        assertEquals("@evilcorp", affected.purlNamespace());
        assertEquals("postinstall-helper", affected.purlName());
        assertNull(affected.introduced(), "no ranges means every version, from the beginning");
        assertNull(affected.fixed(), "and no fix");
        assertNull(affected.lastAffected());
    }

    @Test
    void withdrawnAdvisoriesAreKeptWithTheirRetractionTime() {
        OsvSyncStats stats = new OsvSyncStats();
        NormalizedAdvisory advisory = parse("pypi-withdrawn.json", stats).orElseThrow();

        assertEquals(Instant.parse("2026-02-20T11:04:00Z"), advisory.withdrawnAt());
        assertEquals("MODERATE", advisory.severity());
        assertEquals(1, advisory.affected().size());

        NormalizedAffected affected = advisory.affected().get(0);
        assertEquals("pypi", affected.purlType());
        assertNull(affected.purlNamespace());
        assertEquals("zope-interface", affected.purlName(), "PEP 503 normalisation");
        assertNull(affected.introduced(), "introduced \"0\" means from the beginning, i.e. no bound");
        assertEquals("5.4.0", affected.fixed());
    }

    @Test
    void dropsRecordsWhoseEcosystemsMegaRepoDoesNotHost() {
        OsvSyncStats stats = new OsvSyncStats();
        assertTrue(parse("foreign-ecosystem.json", stats).isEmpty());

        assertEquals(2, stats.skippedForeignEcosystem, "Go and Debian:12");
        assertEquals(1, stats.noAffectedRanges);
    }

    @Test
    void keepsOnlyTheMirroredEcosystemsOfAMixedAdvisory() {
        OsvSyncStats stats = new OsvSyncStats();
        NormalizedAdvisory advisory = parse("mixed-ecosystem.json", stats).orElseThrow();

        assertEquals(1, stats.skippedForeignEcosystem, "crates.io dropped");
        assertEquals(3, advisory.affected().size(), "one npm range plus two enumerated NuGet versions");

        NormalizedAffected npm = advisory.affected().get(0);
        assertEquals("npm", npm.purlType());
        assertEquals("1.0.0", npm.introduced());
        assertNull(npm.fixed());
        assertEquals("1.9.4", npm.lastAffected(), "last_affected is inclusive, unlike fixed");

        NormalizedAffected nuget = advisory.affected().get(1);
        assertEquals("nuget", nuget.purlType());
        assertEquals("shared.parser", nuget.purlName(), "NuGet ids are case-insensitive");
        assertEquals("1.0.0", nuget.introduced());
        assertEquals("1.0.0", nuget.lastAffected(), "an enumerated version is a closed interval");

        assertEquals("HIGH", advisory.severity());
        assertNull(advisory.cvssScore(), "a v4 vector is stored, not scored");
        assertTrue(advisory.cvssVector().startsWith("CVSS:4.0/"));
    }

    @Test
    void dropsGitOnlyRangesRatherThanBlanketBlockingThePackage() {
        OsvSyncStats stats = new OsvSyncStats();
        assertTrue(parse("git-range-only.json", stats).isEmpty());

        assertEquals(1, stats.skippedGitRanges);
        assertEquals(1, stats.skippedUnusableRanges);
        assertEquals(1, stats.noAffectedRanges);
    }

    @Test
    void anUnclosedRangeStaysAffectedWithNoKnownFix() {
        OsvSyncStats stats = new OsvSyncStats();
        NormalizedAdvisory advisory = parse("maven-open-range.json", stats).orElseThrow();

        assertEquals(1, advisory.affected().size());
        NormalizedAffected affected = advisory.affected().get(0);
        assertEquals("maven", affected.purlType(), "the Maven:<repo> qualifier is stripped");
        assertEquals("com.acme.tools", affected.purlNamespace());
        assertEquals("1.2.0", affected.introduced());
        assertNull(affected.fixed());
        assertNull(affected.lastAffected());
        assertNull(advisory.cvssScore());
        assertNull(advisory.severity(), "no vector and no published severity — nothing to derive from");
    }

    @Test
    void dropsMavenEntriesWithoutAGroupId() {
        OsvSyncStats stats = new OsvSyncStats();
        assertTrue(parse("maven-bad-coordinate.json", stats).isEmpty());
        assertEquals(1, stats.skippedUnusablePackageName);
    }

    @Test
    void survivesRecordsThatAreNotOsvAtAll() {
        OsvSyncStats stats = new OsvSyncStats();

        assertTrue(parser.parse(null, stats).isEmpty());
        assertTrue(parser.parse(json("[]"), stats).isEmpty());
        assertTrue(parser.parse(json("{}"), stats).isEmpty(), "no id");
        assertTrue(parser.parse(json("{\"id\":\"X\"}"), stats).isEmpty(), "no affected");
        assertTrue(
                parser.parse(json("{\"id\":\"X\",\"affected\":\"nonsense\"}"), stats).isEmpty());
        assertTrue(
                parser.parse(
                                json("{\"id\":\"X\",\"affected\":[{\"package\":{}}],"
                                        + "\"modified\":\"not-a-date\"}"),
                                stats)
                        .isEmpty());

        assertEquals(3, stats.unusableRecord);
        assertEquals(3, stats.noAffectedRanges);
    }

    @Test
    void refusesIdentifiersAndBoundsTheSchemaCannotStore() {
        OsvSyncStats stats = new OsvSyncStats();

        String longId = "GHSA-" + "x".repeat(OsvRecordParser.MAX_ID);
        assertTrue(
                parser.parse(json("{\"id\":\"" + longId + "\",\"affected\":[]}"), stats).isEmpty());
        assertEquals(1, stats.unusableRecord);

        String longVersion = "1.".repeat(OsvRecordParser.MAX_BOUND);
        assertTrue(
                parser.parse(
                                json("{\"id\":\"GHSA-ok\",\"affected\":[{\"package\":"
                                        + "{\"ecosystem\":\"npm\",\"name\":\"left-pad\"},"
                                        + "\"ranges\":[{\"type\":\"SEMVER\",\"events\":"
                                        + "[{\"introduced\":\"" + longVersion + "\"}]}]}]}"),
                                stats)
                        .isEmpty(),
                "a truncated version compares wrong — the row is dropped instead");
        assertEquals(1, stats.skippedOverlongBounds);
    }

    @Test
    void enumeratedVersionsAreCapped() {
        StringBuilder versions = new StringBuilder();
        int published = OsvRecordParser.MAX_ENUMERATED_VERSIONS + 25;
        for (int i = 0; i < published; i++) {
            versions.append(i == 0 ? "" : ",").append("\"1.0.").append(i).append("\"");
        }
        OsvSyncStats stats = new OsvSyncStats();
        NormalizedAdvisory advisory = parser
                .parse(
                        json("{\"id\":\"GHSA-many\",\"affected\":[{\"package\":"
                                + "{\"ecosystem\":\"PyPI\",\"name\":\"requests\"},"
                                + "\"versions\":[" + versions + "]}]}"),
                        stats)
                .orElseThrow();

        assertEquals(OsvRecordParser.MAX_ENUMERATED_VERSIONS, advisory.affected().size());
        assertEquals(1, stats.truncatedVersionEnumerations);
    }

    @Test
    void fallsBackToDetailsWhenThereIsNoSummary() {
        OsvSyncStats stats = new OsvSyncStats();
        NormalizedAdvisory advisory = parser
                .parse(
                        json("{\"id\":\"MAL-1\",\"details\":\"First paragraph.\\n\\nSecond.\","
                                + "\"affected\":[{\"package\":{\"ecosystem\":\"npm\",\"name\":\"x\"}}]}"),
                        stats)
                .orElseThrow();
        assertEquals("First paragraph.", advisory.summary());
    }

    @Test
    void derivesTheSeverityBandOnlyFromAScoreItActuallyHas() {
        OsvSyncStats stats = new OsvSyncStats();
        NormalizedAdvisory advisory = parser
                .parse(
                        json("{\"id\":\"GHSA-band\",\"severity\":[{\"type\":\"CVSS_V3\",\"score\":"
                                + "\"CVSS:3.1/AV:L/AC:L/PR:L/UI:N/S:U/C:H/I:N/A:N\"}],"
                                + "\"affected\":[{\"package\":{\"ecosystem\":\"npm\",\"name\":\"x\"}}]}"),
                        stats)
                .orElseThrow();
        assertEquals(5.5, advisory.cvssScore(), 0.001);
        assertEquals("MEDIUM", advisory.severity());
    }

    // ------------------------------------------------------------------ setup

    private Optional<NormalizedAdvisory> parse(String fixture, OsvSyncStats stats) {
        return parser.parse(load(fixture), stats);
    }

    private JsonNode json(String raw) {
        try {
            return mapper.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode load(String fixture) {
        try (InputStream in = getClass().getResourceAsStream("/osv/" + fixture)) {
            if (in == null) {
                throw new IllegalStateException("Fixture not found: " + fixture);
            }
            return mapper.readTree(in.readAllBytes());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
