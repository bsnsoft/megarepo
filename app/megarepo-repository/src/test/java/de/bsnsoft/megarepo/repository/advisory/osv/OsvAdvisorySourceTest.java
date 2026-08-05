package de.bsnsoft.megarepo.repository.advisory.osv;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.bsnsoft.megarepo.repository.advisory.AdvisorySyncException;
import de.bsnsoft.megarepo.repository.advisory.AdvisorySyncResult;
import de.bsnsoft.megarepo.repository.advisory.NormalizedAdvisory;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sync loop against in-memory ZIP archives.
 *
 * <p><b>Nothing here goes near the network.</b> {@link StubArchives} stands in for the
 * bulk export, which is the only reason {@link OsvArchiveSource} exists as an interface;
 * the archives are built here out of the same fixtures the parser test uses, so the bytes
 * flowing through {@code ZipInputStream} are real OSV records in a real ZIP.
 */
class OsvAdvisorySourceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void walksOneEcosystemPerCallAndFinishesThePass() throws Exception {
        StubArchives archives = new StubArchives();
        archives.put(OsvEcosystem.MAVEN, entries("maven-log4shell.json", "maven-open-range.json"));
        archives.put(OsvEcosystem.NPM, entries("npm-malicious.json"));
        archives.put(OsvEcosystem.PYPI, entries("pypi-withdrawn.json"));
        archives.put(OsvEcosystem.NUGET, entries("mixed-ecosystem.json"));

        List<NormalizedAdvisory> collected = new ArrayList<>();
        String cursor = null;
        int calls = 0;
        boolean complete = false;
        OsvAdvisorySource source = source(archives, 50_000);

        while (!complete) {
            AdvisorySyncResult result = source.sync(cursor);
            collected.addAll(result.advisories());
            cursor = result.nextCursor();
            complete = result.complete();
            calls++;
            assertTrue(calls <= 10, "the pass must terminate");
        }

        assertEquals(4, calls, "one archive per call");
        assertEquals(
                List.of(OsvEcosystem.MAVEN, OsvEcosystem.NPM, OsvEcosystem.PYPI, OsvEcosystem.NUGET),
                archives.opened);
        assertEquals(5, collected.size());
        assertTrue(collected.stream().allMatch(a -> "OSV".equals(a.source())));
        assertTrue(collected.stream().anyMatch(a -> a.id().startsWith("MAL-")));
        assertNotNull(cursor, "a completed pass still hands back its watermarks");
    }

    @Test
    void resumesInsideAnArchiveWhenTheBatchCapIsHit() throws Exception {
        StubArchives archives = new StubArchives();
        archives.put(
                OsvEcosystem.MAVEN,
                entries(
                        "maven-log4shell.json",
                        "maven-open-range.json",
                        "maven-log4shell.json",
                        "maven-open-range.json",
                        "maven-log4shell.json"));
        OsvAdvisorySource source = source(archives, 2);

        AdvisorySyncResult first = source.sync(null);
        assertEquals(2, first.advisories().size());
        assertFalse(first.complete());
        assertEquals(
                2,
                OsvSyncCursor.parse(first.nextCursor(), mapper).entryOffset(),
                "resume points past the two records already handed over");
        assertEquals(0, OsvSyncCursor.parse(first.nextCursor(), mapper).ecosystemIndex());

        AdvisorySyncResult second = source.sync(first.nextCursor());
        assertEquals(2, second.advisories().size());
        assertFalse(second.complete());
        assertEquals(4, OsvSyncCursor.parse(second.nextCursor(), mapper).entryOffset());

        AdvisorySyncResult third = source.sync(second.nextCursor());
        assertEquals(1, third.advisories().size());
        assertEquals(
                1,
                OsvSyncCursor.parse(third.nextCursor(), mapper).ecosystemIndex(),
                "the archive is done, so the cursor moves on to npm");

        assertEquals(3, archives.opened.size(), "each resume re-opens the archive");
        // Five records over three batches, none lost and none repeated.
        List<String> ids = new ArrayList<>();
        first.advisories().forEach(a -> ids.add(a.id()));
        second.advisories().forEach(a -> ids.add(a.id()));
        third.advisories().forEach(a -> ids.add(a.id()));
        assertEquals(5, ids.size());
    }

    @Test
    void aBrokenEntryCostsOneRecordNotTheSync() throws Exception {
        Map<String, byte[]> archive = new LinkedHashMap<>();
        archive.put("GHSA-jfh8-c2jp-5v3q.json", fixture("maven-log4shell.json"));
        archive.put("truncated.json", "{\"id\": \"GHSA-broken\", \"affected\": [".getBytes(StandardCharsets.UTF_8));
        archive.put("empty.json", new byte[0]);
        archive.put("not-json-at-all.json", "<html>404</html>".getBytes(StandardCharsets.UTF_8));
        archive.put("README.txt", "ignored".getBytes(StandardCharsets.UTF_8));
        archive.put("GHSA-open-1111-2222.json", fixture("maven-open-range.json"));

        StubArchives archives = new StubArchives();
        archives.put(OsvEcosystem.MAVEN, archive);

        AdvisorySyncResult result = source(archives, 50_000).sync(null);

        assertEquals(2, result.advisories().size(), "both good records survive the three bad ones");
        assertFalse(result.complete(), "Maven is done, three ecosystems remain");
    }

    @Test
    void recordsFromEcosystemsMegaRepoDoesNotHostNeverReachTheStore() throws Exception {
        StubArchives archives = new StubArchives();
        archives.put(
                OsvEcosystem.MAVEN,
                entries("foreign-ecosystem.json", "git-range-only.json", "maven-bad-coordinate.json"));

        AdvisorySyncResult result = source(archives, 50_000).sync(null);

        assertTrue(result.advisories().isEmpty());
        assertNotNull(result.nextCursor(), "a batch of nothing is still progress");
    }

    @Test
    void anUnreachableArchiveIsTheOnlyThingThatThrows() {
        StubArchives archives = new StubArchives();
        archives.failure = new AdvisorySyncException("connect timed out");
        OsvAdvisorySource source = source(archives, 50_000);

        AdvisorySyncException thrown = assertThrows(AdvisorySyncException.class, () -> source.sync(null));
        assertEquals("connect timed out", thrown.getMessage());
    }

    @Test
    void aTruncatedDownloadIsReportedAsASyncFailure() {
        StubArchives archives = new StubArchives();
        byte[] complete = zip(entries("maven-log4shell.json"));
        archives.rawBytes.put(OsvEcosystem.MAVEN, Arrays.copyOf(complete, complete.length / 2));
        OsvAdvisorySource source = source(archives, 50_000);

        // Half a ZIP is upstream returning something unusable, which is the one case the
        // contract wants raised rather than counted.
        assertThrows(AdvisorySyncException.class, () -> source.sync(null));
    }

    @Test
    void anOversizedEntryIsSkippedWithoutReadingItIntoTheHeap() throws Exception {
        Map<String, byte[]> archive = new LinkedHashMap<>();
        archive.put("huge.json", ("{\"padding\":\"" + "x".repeat(20_000) + "\"}").getBytes(StandardCharsets.UTF_8));
        archive.put("GHSA-jfh8-c2jp-5v3q.json", fixture("maven-log4shell.json"));

        StubArchives archives = new StubArchives();
        archives.put(OsvEcosystem.MAVEN, archive);

        OsvAdvisorySource source = new OsvAdvisorySource(archives, mapper, 50_000, 4096, Duration.ofDays(7));
        AdvisorySyncResult result = source.sync(null);

        assertEquals(1, result.advisories().size());
        assertEquals("GHSA-jfh8-c2jp-5v3q", result.advisories().get(0).id());
    }

    @Test
    void thePassAfterAFullOneOnlyHandsOverWhatChanged() throws Exception {
        // Two Maven records a month and a half apart. After the first (unfiltered) pass
        // the watermark sits on the newer one, so the older one has nothing to say.
        StubArchives archives = new StubArchives();
        archives.put(
                OsvEcosystem.MAVEN,
                entries("maven-log4shell.json", "maven-open-range.json"));
        OsvAdvisorySource source = source(archives, 50_000);

        assertEquals(2, countAdvisories(source, null).size());

        String afterFullPass = lastCursor;
        List<String> second = countAdvisories(source, afterFullPass);
        assertEquals(
                List.of("GHSA-open-1111-2222"),
                second,
                "only the record at the watermark comes back, and only because of the overlap");

        // And the pass after that is still incremental: the watermark did not move.
        assertEquals(second, countAdvisories(source, lastCursor));
    }

    @Test
    void aFullRefreshReEmitsEverythingTheWatermarkWouldHaveHidden() throws Exception {
        StubArchives archives = new StubArchives();
        archives.put(
                OsvEcosystem.MAVEN,
                entries("maven-log4shell.json", "maven-open-range.json"));
        // A zero interval means every pass is a full one.
        OsvAdvisorySource source =
                new OsvAdvisorySource(archives, mapper, 50_000, 2 * 1024 * 1024, Duration.ZERO);

        assertEquals(2, countAdvisories(source, null).size());
        assertEquals(2, countAdvisories(source, lastCursor).size());
    }

    @Test
    void withdrawnAdvisoriesAreIngestedSoAComponentCanBeCleared() throws Exception {
        StubArchives archives = new StubArchives();
        archives.put(OsvEcosystem.PYPI, entries("pypi-withdrawn.json"));
        // Start the pass at PyPI so the assertion does not depend on the other archives.
        String cursor = new OsvSyncCursor(2, 0, Map.of(), null, null, true).format(mapper);

        AdvisorySyncResult result = source(archives, 50_000).sync(cursor);

        assertEquals(1, result.advisories().size());
        assertNotNull(result.advisories().get(0).withdrawnAt());
    }

    @Test
    void theSourceIdIsStableAndMatchesWhatItStamps() throws Exception {
        StubArchives archives = new StubArchives();
        archives.put(OsvEcosystem.MAVEN, entries("maven-log4shell.json"));
        OsvAdvisorySource source = source(archives, 50_000);

        assertEquals("OSV", source.sourceId());
        assertEquals(source.sourceId(), source.sync(null).advisories().get(0).source());
    }

    @Test
    void anEmptyArchiveIsAValidAnswer() throws Exception {
        StubArchives archives = new StubArchives();
        AdvisorySyncResult result = source(archives, 50_000).sync(null);
        assertTrue(result.advisories().isEmpty());
        assertNull(OsvSyncCursor.parse(result.nextCursor(), mapper).currentMax());
    }

    // ------------------------------------------------------------------ setup

    /** The cursor the last {@link #countAdvisories} loop finished on. */
    private String lastCursor;

    private OsvAdvisorySource source(OsvArchiveSource archives, int maxBatchSize) {
        return new OsvAdvisorySource(archives, mapper, maxBatchSize, 2 * 1024 * 1024, Duration.ofDays(7));
    }

    /** Runs one whole pass and returns the ids it emitted, in order. */
    private List<String> countAdvisories(OsvAdvisorySource source, String cursor)
            throws AdvisorySyncException {
        List<String> ids = new ArrayList<>();
        boolean complete = false;
        int calls = 0;
        while (!complete) {
            AdvisorySyncResult result = source.sync(cursor);
            result.advisories().forEach(advisory -> ids.add(advisory.id()));
            cursor = result.nextCursor();
            complete = result.complete();
            assertTrue(++calls <= 20, "the pass must terminate");
        }
        lastCursor = cursor;
        return ids;
    }

    private Map<String, byte[]> entries(String... fixtures) {
        Map<String, byte[]> archive = new LinkedHashMap<>();
        for (int i = 0; i < fixtures.length; i++) {
            archive.put(i + "-" + fixtures[i], fixture(fixtures[i]));
        }
        return archive;
    }

    private byte[] fixture(String name) {
        try (InputStream in = getClass().getResourceAsStream("/osv/" + name)) {
            if (in == null) {
                throw new IllegalStateException("Fixture not found: " + name);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] zip(Map<String, byte[]> entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /** Stands in for the bulk export; the tests never open a socket. */
    private static final class StubArchives implements OsvArchiveSource {

        private final Map<OsvEcosystem, byte[]> rawBytes = new EnumMap<>(OsvEcosystem.class);
        private final List<OsvEcosystem> opened = new ArrayList<>();
        private AdvisorySyncException failure;

        void put(OsvEcosystem ecosystem, Map<String, byte[]> entries) {
            rawBytes.put(ecosystem, zip(entries));
        }

        @Override
        public InputStream openArchive(OsvEcosystem ecosystem) throws AdvisorySyncException {
            opened.add(ecosystem);
            if (failure != null) {
                throw failure;
            }
            byte[] archive = rawBytes.get(ecosystem);
            return new ByteArrayInputStream(archive != null ? archive : zip(Map.of()));
        }
    }

}
