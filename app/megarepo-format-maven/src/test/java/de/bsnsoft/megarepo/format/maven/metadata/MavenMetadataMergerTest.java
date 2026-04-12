package de.bsnsoft.megarepo.format.maven.metadata;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MavenMetadataMergerTest {

    private MavenMetadataMerger merger;

    @BeforeEach
    void setUp() {
        merger = new MavenMetadataMerger();
    }

    @Test
    void mergeMetadata_overlappingVersions_deduplicatedAndSorted() {
        var meta1 = new MavenMetadataModel(
                "com.example",
                "my-lib",
                null,
                new MavenMetadataModel.Versioning(
                        "1.2", "1.2", List.of("1.0", "1.2"), "20260328100000", null, null));

        var meta2 = new MavenMetadataModel(
                "com.example",
                "my-lib",
                null,
                new MavenMetadataModel.Versioning(
                        "1.1", "1.1", List.of("1.1", "1.2"), "20260328110000", null, null));

        MavenMetadataModel merged = merger.mergeMetadata(List.of(meta1, meta2));

        assertNotNull(merged.versioning());
        assertEquals(List.of("1.0", "1.1", "1.2"), merged.versioning().versions());
        assertEquals("1.2", merged.versioning().latest());
        assertEquals("1.2", merged.versioning().release());
        assertEquals("20260328110000", merged.versioning().lastUpdated());
    }

    @Test
    void mergeMetadata_oneEmptyVersionsList_returnsOther() {
        var meta1 = new MavenMetadataModel(
                "com.example",
                "my-lib",
                null,
                new MavenMetadataModel.Versioning(
                        "2.0", "2.0", List.of("1.0", "2.0"), "20260328120000", null, null));

        var meta2 = new MavenMetadataModel(
                "com.example",
                "my-lib",
                null,
                new MavenMetadataModel.Versioning(null, null, null, "20260328100000", null, null));

        MavenMetadataModel merged = merger.mergeMetadata(List.of(meta1, meta2));

        assertNotNull(merged.versioning());
        assertEquals(List.of("1.0", "2.0"), merged.versioning().versions());
        assertEquals("2.0", merged.versioning().latest());
        assertEquals("2.0", merged.versioning().release());
    }

    @Test
    void mergeMetadata_differentLatest_picksCorrectLatest() {
        var meta1 = new MavenMetadataModel(
                "com.example",
                "my-lib",
                null,
                new MavenMetadataModel.Versioning(
                        "3.0", "3.0", List.of("3.0"), "20260328100000", null, null));

        var meta2 = new MavenMetadataModel(
                "com.example",
                "my-lib",
                null,
                new MavenMetadataModel.Versioning(
                        "2.5", "2.5", List.of("1.0", "2.5"), "20260328090000", null, null));

        MavenMetadataModel merged = merger.mergeMetadata(List.of(meta1, meta2));

        assertNotNull(merged.versioning());
        assertEquals(List.of("1.0", "2.5", "3.0"), merged.versioning().versions());
        assertEquals("3.0", merged.versioning().latest());
        assertEquals("3.0", merged.versioning().release());
    }

    @Test
    void mergeMetadata_emptyList_throwsException() {
        assertThrows(IllegalArgumentException.class, () -> merger.mergeMetadata(List.of()));
    }

    @Test
    void mergeMetadata_singleSource_returnsSameModel() {
        var meta = new MavenMetadataModel(
                "com.example",
                "my-lib",
                null,
                new MavenMetadataModel.Versioning(
                        "1.0", "1.0", List.of("1.0"), "20260328120000", null, null));

        MavenMetadataModel merged = merger.mergeMetadata(List.of(meta));

        assertEquals(meta, merged);
    }

    @Test
    void mergeMetadata_snapshotAndRelease_releaseIgnoresSnapshots() {
        var meta1 = new MavenMetadataModel(
                "com.example",
                "my-lib",
                null,
                new MavenMetadataModel.Versioning(
                        "2.0-SNAPSHOT",
                        null,
                        List.of("2.0-SNAPSHOT"),
                        "20260328120000",
                        null,
                        null));

        var meta2 = new MavenMetadataModel(
                "com.example",
                "my-lib",
                null,
                new MavenMetadataModel.Versioning(
                        "1.0", "1.0", List.of("1.0"), "20260328100000", null, null));

        MavenMetadataModel merged = merger.mergeMetadata(List.of(meta1, meta2));

        assertEquals("1.0", merged.versioning().release());
        // Latest is the last sorted version; SNAPSHOT sorts after release due to text comparison
        assertNotNull(merged.versioning().latest());
    }
}
