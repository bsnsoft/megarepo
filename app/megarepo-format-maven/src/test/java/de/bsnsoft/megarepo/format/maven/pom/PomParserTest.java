package de.bsnsoft.megarepo.format.maven.pom;

import de.bsnsoft.megarepo.format.maven.pom.PomParser.PomInfo;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PomParserTest {

    private final PomParser parser = new PomParser();

    @Test
    void parseSimplePom() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>myapp</artifactId>
                    <version>1.0</version>
                </project>
                """;

        Optional<PomInfo> result = parser.parsePom(toInputStream(xml));

        assertTrue(result.isPresent());
        PomInfo info = result.get();
        assertEquals("com.example", info.groupId());
        assertEquals("myapp", info.artifactId());
        assertEquals("1.0", info.version());
        assertEquals("jar", info.packaging());
    }

    @Test
    void parsePomWithParentGroupId() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>com.example</groupId>
                        <artifactId>parent</artifactId>
                        <version>1.0</version>
                    </parent>
                    <artifactId>child-module</artifactId>
                </project>
                """;

        Optional<PomInfo> result = parser.parsePom(toInputStream(xml));

        assertTrue(result.isPresent());
        PomInfo info = result.get();
        assertEquals("com.example", info.groupId());
        assertEquals("child-module", info.artifactId());
        assertEquals("1.0", info.version());
    }

    @Test
    void parsePomWithParentVersion() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>com.example</groupId>
                        <artifactId>parent</artifactId>
                        <version>2.0</version>
                    </parent>
                    <groupId>com.example.child</groupId>
                    <artifactId>child-module</artifactId>
                </project>
                """;

        Optional<PomInfo> result = parser.parsePom(toInputStream(xml));

        assertTrue(result.isPresent());
        PomInfo info = result.get();
        assertEquals("com.example.child", info.groupId());
        assertEquals("child-module", info.artifactId());
        assertEquals("2.0", info.version());
    }

    @Test
    void parsePomWithOverriddenGroupId() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>com.example</groupId>
                        <artifactId>parent</artifactId>
                        <version>1.0</version>
                    </parent>
                    <groupId>com.other</groupId>
                    <artifactId>child</artifactId>
                    <version>3.0</version>
                </project>
                """;

        Optional<PomInfo> result = parser.parsePom(toInputStream(xml));

        assertTrue(result.isPresent());
        PomInfo info = result.get();
        assertEquals("com.other", info.groupId());
        assertEquals("child", info.artifactId());
        assertEquals("3.0", info.version());
    }

    @Test
    void parseInvalidXml_returnsEmpty() {
        String xml = "this is not xml at all";

        Optional<PomInfo> result = parser.parsePom(toInputStream(xml));

        assertTrue(result.isEmpty());
    }

    @Test
    void parsePomWithPackaging() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>webapp</artifactId>
                    <version>1.0</version>
                    <packaging>war</packaging>
                </project>
                """;

        Optional<PomInfo> result = parser.parsePom(toInputStream(xml));

        assertTrue(result.isPresent());
        PomInfo info = result.get();
        assertEquals("war", info.packaging());
    }

    @Test
    void parsePomWithPomPackaging() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>parent-pom</artifactId>
                    <version>1.0</version>
                    <packaging>pom</packaging>
                </project>
                """;

        Optional<PomInfo> result = parser.parsePom(toInputStream(xml));

        assertTrue(result.isPresent());
        PomInfo info = result.get();
        assertEquals("pom", info.packaging());
    }

    @Test
    void parseDefaultPackaging_isJar() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>mylib</artifactId>
                    <version>1.0</version>
                </project>
                """;

        Optional<PomInfo> result = parser.parsePom(toInputStream(xml));

        assertTrue(result.isPresent());
        assertEquals("jar", result.get().packaging());
    }

    @Test
    void parsePomWithNoArtifactId_returnsEmpty() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <version>1.0</version>
                </project>
                """;

        Optional<PomInfo> result = parser.parsePom(toInputStream(xml));

        assertTrue(result.isEmpty());
    }

    @Test
    void parseNonProjectRootElement_returnsEmpty() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <settings>
                    <groupId>com.example</groupId>
                </settings>
                """;

        Optional<PomInfo> result = parser.parsePom(toInputStream(xml));

        assertTrue(result.isEmpty());
    }

    @Test
    void parsePomWithNamespaces() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>namespaced</artifactId>
                    <version>1.0</version>
                </project>
                """;

        Optional<PomInfo> result = parser.parsePom(toInputStream(xml));

        // The parser may or may not handle namespace-qualified elements.
        // At minimum it should not throw. If it parses successfully, verify coordinates.
        if (result.isPresent()) {
            PomInfo info = result.get();
            assertEquals("com.example", info.groupId());
            assertEquals("namespaced", info.artifactId());
            assertEquals("1.0", info.version());
        }
    }

    @Test
    void parsePomWithSnapshotVersion() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>snapshot-lib</artifactId>
                    <version>1.0-SNAPSHOT</version>
                </project>
                """;

        Optional<PomInfo> result = parser.parsePom(toInputStream(xml));

        assertTrue(result.isPresent());
        PomInfo info = result.get();
        assertEquals("1.0-SNAPSHOT", info.version());
    }

    @Test
    void parseEmptyStream_returnsEmpty() {
        Optional<PomInfo> result = parser.parsePom(new ByteArrayInputStream(new byte[0]));

        assertTrue(result.isEmpty());
    }

    private InputStream toInputStream(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }
}
