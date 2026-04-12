package de.bsnsoft.megarepo.format.maven;

import de.bsnsoft.megarepo.core.format.ComponentCoordinates;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MavenCoordinateExtractorTest {

    private MavenCoordinateExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new MavenCoordinateExtractor();
    }

    @Test
    void extractFromPath_standardJar() {
        Optional<ComponentCoordinates> result =
                extractor.extractFromPath("org/apache/commons/commons-lang3/3.14.0/commons-lang3-3.14.0.jar");

        assertTrue(result.isPresent());
        ComponentCoordinates coords = result.get();
        assertEquals("org.apache.commons", coords.namespace());
        assertEquals("commons-lang3", coords.name());
        assertEquals("3.14.0", coords.version());
        assertEquals("jar", coords.formatAttributes().get("extension"));
        assertEquals("", coords.formatAttributes().get("classifier"));
    }

    @Test
    void extractFromPath_sourcesJar() {
        Optional<ComponentCoordinates> result = extractor.extractFromPath(
                "org/apache/commons/commons-lang3/3.14.0/commons-lang3-3.14.0-sources.jar");

        assertTrue(result.isPresent());
        ComponentCoordinates coords = result.get();
        assertEquals("org.apache.commons", coords.namespace());
        assertEquals("commons-lang3", coords.name());
        assertEquals("3.14.0", coords.version());
        assertEquals("jar", coords.formatAttributes().get("extension"));
        assertEquals("sources", coords.formatAttributes().get("classifier"));
    }

    @Test
    void extractFromPath_javadocJar() {
        Optional<ComponentCoordinates> result = extractor.extractFromPath(
                "org/example/mylib/1.0/mylib-1.0-javadoc.jar");

        assertTrue(result.isPresent());
        ComponentCoordinates coords = result.get();
        assertEquals("org.example", coords.namespace());
        assertEquals("mylib", coords.name());
        assertEquals("1.0", coords.version());
        assertEquals("jar", coords.formatAttributes().get("extension"));
        assertEquals("javadoc", coords.formatAttributes().get("classifier"));
    }

    @Test
    void extractFromPath_testsJar() {
        Optional<ComponentCoordinates> result =
                extractor.extractFromPath("com/example/mylib/1.0/mylib-1.0-tests.jar");

        assertTrue(result.isPresent());
        ComponentCoordinates coords = result.get();
        assertEquals("tests", coords.formatAttributes().get("classifier"));
        assertEquals("jar", coords.formatAttributes().get("extension"));
    }

    @Test
    void extractFromPath_pomFile() {
        Optional<ComponentCoordinates> result =
                extractor.extractFromPath("org/apache/commons/commons-lang3/3.14.0/commons-lang3-3.14.0.pom");

        assertTrue(result.isPresent());
        ComponentCoordinates coords = result.get();
        assertEquals("org.apache.commons", coords.namespace());
        assertEquals("commons-lang3", coords.name());
        assertEquals("3.14.0", coords.version());
        assertEquals("pom", coords.formatAttributes().get("extension"));
        assertEquals("", coords.formatAttributes().get("classifier"));
    }

    @Test
    void extractFromPath_snapshotTimestamped() {
        Optional<ComponentCoordinates> result = extractor.extractFromPath(
                "com/example/foo/1.0-SNAPSHOT/foo-20220101.120000-1.jar");

        assertTrue(result.isPresent());
        ComponentCoordinates coords = result.get();
        assertEquals("com.example", coords.namespace());
        assertEquals("foo", coords.name());
        assertEquals("1.0-SNAPSHOT", coords.version());
        assertEquals("jar", coords.formatAttributes().get("extension"));
        assertEquals("", coords.formatAttributes().get("classifier"));
    }

    @Test
    void extractFromPath_snapshotNonTimestamped() {
        Optional<ComponentCoordinates> result =
                extractor.extractFromPath("com/example/foo/1.0-SNAPSHOT/foo-1.0-SNAPSHOT.jar");

        assertTrue(result.isPresent());
        ComponentCoordinates coords = result.get();
        assertEquals("com.example", coords.namespace());
        assertEquals("foo", coords.name());
        assertEquals("1.0-SNAPSHOT", coords.version());
        assertEquals("jar", coords.formatAttributes().get("extension"));
        assertEquals("", coords.formatAttributes().get("classifier"));
    }

    @Test
    void extractFromPath_snapshotTimestampedPom() {
        Optional<ComponentCoordinates> result = extractor.extractFromPath(
                "com/example/app/1.0-SNAPSHOT/app-1.0-20260315.093000-5.pom");

        assertTrue(result.isPresent());
        ComponentCoordinates coords = result.get();
        assertEquals("com.example", coords.namespace());
        assertEquals("app", coords.name());
        assertEquals("1.0-SNAPSHOT", coords.version());
        assertEquals("pom", coords.formatAttributes().get("extension"));
    }

    @Test
    void extractFromPath_metadataFile_returnsEmpty() {
        Optional<ComponentCoordinates> result =
                extractor.extractFromPath("org/apache/commons/commons-lang3/3.14.0/maven-metadata.xml");

        assertTrue(result.isEmpty());
    }

    @Test
    void extractFromPath_metadataChecksumFile_returnsEmpty() {
        Optional<ComponentCoordinates> result =
                extractor.extractFromPath("org/apache/commons/commons-lang3/maven-metadata.xml.sha1");

        assertTrue(result.isEmpty());
    }

    @Test
    void extractFromPath_checksumPath_returnsEmpty() {
        assertTrue(extractor
                .extractFromPath("org/apache/commons/commons-lang3/3.14.0/commons-lang3-3.14.0.jar.sha1")
                .isEmpty());
        assertTrue(extractor
                .extractFromPath("org/apache/commons/commons-lang3/3.14.0/commons-lang3-3.14.0.jar.md5")
                .isEmpty());
    }

    @Test
    void extractFromPath_rootLevelFile_returnsEmpty() {
        Optional<ComponentCoordinates> result = extractor.extractFromPath("file.jar");

        assertTrue(result.isEmpty());
    }

    @Test
    void extractFromPath_tooFewSegments_returnsEmpty() {
        assertTrue(extractor.extractFromPath("org/artifact/file.jar").isEmpty());
    }

    @Test
    void extractFromPath_nullPath_returnsEmpty() {
        assertTrue(extractor.extractFromPath(null).isEmpty());
    }

    @Test
    void extractFromPath_emptyPath_returnsEmpty() {
        assertTrue(extractor.extractFromPath("").isEmpty());
    }

    @Test
    void extractFromPath_blankPath_returnsEmpty() {
        assertTrue(extractor.extractFromPath("   ").isEmpty());
    }

    @Test
    void extractFromPath_leadingSlashIsStripped() {
        Optional<ComponentCoordinates> result =
                extractor.extractFromPath("/org/apache/commons/commons-lang3/3.14.0/commons-lang3-3.14.0.jar");

        assertTrue(result.isPresent());
        ComponentCoordinates coords = result.get();
        assertEquals("org.apache.commons", coords.namespace());
        assertEquals("commons-lang3", coords.name());
        assertEquals("3.14.0", coords.version());
    }

    @Test
    void extractFromPath_deepGroupId() {
        Optional<ComponentCoordinates> result = extractor.extractFromPath(
                "org/apache/maven/plugins/maven-compiler-plugin/3.11.0/maven-compiler-plugin-3.11.0.jar");

        assertTrue(result.isPresent());
        ComponentCoordinates coords = result.get();
        assertEquals("org.apache.maven.plugins", coords.namespace());
        assertEquals("maven-compiler-plugin", coords.name());
        assertEquals("3.11.0", coords.version());
        assertEquals("jar", coords.formatAttributes().get("extension"));
    }

    @Test
    void extractFromPath_singleSegmentGroupId() {
        Optional<ComponentCoordinates> result =
                extractor.extractFromPath("commons-io/commons-io/2.15.1/commons-io-2.15.1.jar");

        assertTrue(result.isPresent());
        ComponentCoordinates coords = result.get();
        assertEquals("commons-io", coords.namespace());
        assertEquals("commons-io", coords.name());
        assertEquals("2.15.1", coords.version());
        assertEquals("jar", coords.formatAttributes().get("extension"));
    }

    @Test
    void extractFromPath_warArtifact() {
        Optional<ComponentCoordinates> result =
                extractor.extractFromPath("com/example/webapp/1.0/webapp-1.0.war");

        assertTrue(result.isPresent());
        assertEquals("war", result.get().formatAttributes().get("extension"));
    }

    @Test
    void extractFromContent_delegatesToExtractFromPath() {
        Optional<ComponentCoordinates> result = extractor.extractFromContent(
                InputStream.nullInputStream(),
                "org/apache/commons/commons-lang3/3.14.0/commons-lang3-3.14.0.jar",
                Map.of());

        assertTrue(result.isPresent());
        assertEquals("org.apache.commons", result.get().namespace());
    }
}
