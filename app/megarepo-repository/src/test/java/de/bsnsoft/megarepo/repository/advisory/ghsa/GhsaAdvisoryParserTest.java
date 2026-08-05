package de.bsnsoft.megarepo.repository.advisory.ghsa;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.bsnsoft.megarepo.repository.advisory.NormalizedAdvisory;
import de.bsnsoft.megarepo.repository.advisory.NormalizedAffected;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parses a realistic page of GitHub's advisories JSON. The fixture deliberately mixes
 * good entries with the three kinds of rubbish a curated-by-many-hands feed produces:
 * ecosystems MegaRepo does not host, a Maven name without its groupId, and entries that
 * are structurally wrong.
 */
class GhsaAdvisoryParserTest {

    private final GhsaAdvisoryParser parser = new GhsaAdvisoryParser(new ObjectMapper(), "GHSA");
    private final GhsaAdvisoryParser.Stats stats = new GhsaAdvisoryParser.Stats();

    @Test
    void parsesTheGoodEntriesAndSkipsTheRest() throws Exception {
        List<NormalizedAdvisory> advisories = parser.parsePage(fixture("/ghsa/page1.json"), stats);

        assertEquals(
                List.of(
                        "GHSA-jfh8-c2jp-5v3q",
                        "GHSA-67hx-6x53-jw92",
                        "GHSA-rmr5-cpv2-vgjf",
                        "GHSA-5crp-9r3c-p9vr"),
                advisories.stream().map(NormalizedAdvisory::id).toList());

        assertEquals(4, stats.advisories);
        assertEquals(1, stats.skippedMalformed, "the entry without a ghsa_id");
        assertEquals(2, stats.skippedForeignEcosystem, "the go and rubygems packages");
        assertEquals(1, stats.skippedUnusablePackage, "the Maven name without a groupId");
        assertEquals(
                3,
                stats.skippedNothingAffected,
                "the go-only advisory, the groupId-less one and the malformed vulnerabilities");
    }

    @Test
    void mapsAdvisoryMetadata() throws Exception {
        NormalizedAdvisory log4shell = byId(parser.parsePage(fixture("/ghsa/page1.json"), stats))
                .get("GHSA-jfh8-c2jp-5v3q");

        assertEquals("GHSA", log4shell.source());
        assertEquals("CRITICAL", log4shell.severity(), "severity is upper-cased, not translated");
        assertEquals(10.0, log4shell.cvssScore(), 0.001);
        assertEquals("CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:C/C:H/I:H/A:H", log4shell.cvssVector());
        assertEquals(Instant.parse("2021-12-10T16:51:53Z"), log4shell.published());
        assertEquals(Instant.parse("2024-02-05T21:53:12Z"), log4shell.modified());
        assertNull(log4shell.withdrawnAt());
        assertNotNull(log4shell.summary());
        assertTrue(log4shell.summary().contains("Log4j"));
    }

    @Test
    void mavenAffectedCarriesGroupIdAsNamespaceAndKeepsTheRawRange() throws Exception {
        NormalizedAdvisory log4shell = byId(parser.parsePage(fixture("/ghsa/page1.json"), stats))
                .get("GHSA-jfh8-c2jp-5v3q");

        assertEquals(2, log4shell.affected().size());
        NormalizedAffected first = log4shell.affected().get(0);
        assertEquals("maven", first.purlType());
        assertEquals("org.apache.logging.log4j", first.purlNamespace());
        assertEquals("log4j-core", first.purlName());
        assertEquals(">= 2.13.0, < 2.15.0", first.versionRange());
        assertEquals("2.13.0", first.introduced());
        assertEquals("2.15.0", first.fixed());
        assertNull(first.lastAffected());
    }

    @Test
    void npmScopeIsSplitAndTheUnfixedEntryKeepsItsLastAffected() throws Exception {
        NormalizedAdvisory babel = byId(parser.parsePage(fixture("/ghsa/page1.json"), stats))
                .get("GHSA-67hx-6x53-jw92");

        NormalizedAffected scoped = babel.affected().get(0);
        assertEquals("npm", scoped.purlType());
        assertEquals("@babel", scoped.purlNamespace());
        assertEquals("traverse", scoped.purlName());
        assertEquals("7.23.2", scoped.fixed());

        NormalizedAffected legacy = babel.affected().get(1);
        assertNull(legacy.purlNamespace());
        assertEquals("babel-traverse", legacy.purlName());
        assertNull(legacy.fixed());
        assertEquals("6.26.0", legacy.lastAffected());
    }

    @Test
    void pypiNameIsNormalisedAndTheScoreFallsBackToCvssSeverities() throws Exception {
        NormalizedAdvisory flaskCors = byId(parser.parsePage(fixture("/ghsa/page1.json"), stats))
                .get("GHSA-rmr5-cpv2-vgjf");

        NormalizedAffected affected = flaskCors.affected().get(0);
        assertEquals("pypi", affected.purlType());
        assertNull(affected.purlNamespace());
        assertEquals("flask-cors", affected.purlName(), "Flask_Cors normalised per PEP 503");
        assertEquals("1.0.0", affected.introduced());
        assertEquals("4.0.0", affected.lastAffected());

        assertEquals("MODERATE", flaskCors.severity());
        assertEquals(5.3, flaskCors.cvssScore(), 0.001, "cvss.score was null, cvss_v3 wins");
        assertEquals("CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:L/I:N/A:N", flaskCors.cvssVector());
    }

    @Test
    void nugetIdIsLowercased() throws Exception {
        NormalizedAdvisory json = byId(parser.parsePage(fixture("/ghsa/page1.json"), stats))
                .get("GHSA-5crp-9r3c-p9vr");

        NormalizedAffected affected = json.affected().get(0);
        assertEquals("nuget", affected.purlType());
        assertEquals("newtonsoft.json", affected.purlName());
        assertEquals("13.0.1", affected.fixed());
    }

    @Test
    void withdrawnAdvisoryIsKeptSoAFlaggedComponentCanBeCleared() throws Exception {
        List<NormalizedAdvisory> advisories = parser.parsePage(fixture("/ghsa/page2.json"), stats);

        NormalizedAdvisory withdrawn = advisories.get(0);
        assertEquals("GHSA-4w2v-q235-vp99", withdrawn.id());
        assertEquals(Instant.parse("2024-06-30T12:00:00Z"), withdrawn.withdrawnAt());
        // first_patched_version in its object form, as the GraphQL/repository shape uses.
        assertEquals("2.9.10", withdrawn.affected().get(0).fixed());
        assertEquals("2.0.0", withdrawn.affected().get(0).introduced());
    }

    @Test
    void aPageThatIsNotAnArrayIsAProtocolFailure() {
        assertThrows(
                IOException.class,
                () -> parser.parsePage("{\"message\":\"Bad credentials\"}", stats));
        assertThrows(IOException.class, () -> parser.parsePage("not json at all", stats));
    }

    @Test
    void anEmptyPageIsFine() throws Exception {
        assertEquals(List.of(), parser.parsePage("[]", stats));
        assertEquals(0, stats.advisories);
    }

    private static Map<String, NormalizedAdvisory> byId(List<NormalizedAdvisory> advisories) {
        return advisories.stream()
                .collect(Collectors.toMap(NormalizedAdvisory::id, Function.identity()));
    }

    private static String fixture(String path) throws IOException {
        try (InputStream in = GhsaAdvisoryParserTest.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Fixture not found: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
