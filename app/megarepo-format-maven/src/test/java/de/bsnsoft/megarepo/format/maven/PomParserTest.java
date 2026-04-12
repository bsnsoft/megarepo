package de.bsnsoft.megarepo.format.maven;

import de.bsnsoft.megarepo.format.maven.pom.PomParser;
import de.bsnsoft.megarepo.format.maven.pom.PomParser.PomInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PomParserTest {

    private PomParser parser;

    @BeforeEach
    void setUp() {
        parser = new PomParser();
    }

    @Test
    void parsePom_simpleGav() {
        String pom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <groupId>org.example</groupId>
                    <artifactId>my-lib</artifactId>
                    <version>1.0.0</version>
                    <packaging>jar</packaging>
                </project>
                """;

        Optional<PomInfo> result = parser.parsePom(toInputStream(pom));

        assertTrue(result.isPresent());
        PomInfo info = result.get();
        assertEquals("org.example", info.groupId());
        assertEquals("my-lib", info.artifactId());
        assertEquals("1.0.0", info.version());
        assertEquals("jar", info.packaging());
    }

    @Test
    void parsePom_withParentInheritance() {
        String pom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <parent>
                        <groupId>org.example.parent</groupId>
                        <artifactId>parent-pom</artifactId>
                        <version>2.0.0</version>
                    </parent>
                    <artifactId>child-module</artifactId>
                </project>
                """;

        Optional<PomInfo> result = parser.parsePom(toInputStream(pom));

        assertTrue(result.isPresent());
        PomInfo info = result.get();
        assertEquals("org.example.parent", info.groupId());
        assertEquals("child-module", info.artifactId());
        assertEquals("2.0.0", info.version());
        assertEquals("jar", info.packaging());
    }

    @Test
    void parsePom_invalidXml_returnsEmpty() {
        String invalidXml = "this is not xml at all <<<<";

        Optional<PomInfo> result = parser.parsePom(toInputStream(invalidXml));

        assertTrue(result.isEmpty());
    }

    private ByteArrayInputStream toInputStream(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }
}
