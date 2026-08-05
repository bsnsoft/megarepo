package de.bsnsoft.megarepo.format.raw.firewall;

import de.bsnsoft.megarepo.database.entity.ComponentEntity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RawPurlMapperTest {

    private final RawPurlMapper mapper = new RawPurlMapper();

    @Test
    void declaresRaw() {
        assertEquals("raw", mapper.format());
        assertTrue(mapper.formatAliases().isEmpty());
    }

    @Test
    void aRawFileHasNoPurlIdentity() {
        // RawCoordinateExtractor stores the directory as namespace, the filename
        // as name and the literal "1" as version for every file — no package, no
        // publisher, no version. PurlBuilder falls back to the file's SHA-256.
        assertTrue(mapper.toPurl(component("files/2024", "report.pdf", "1")).isEmpty());
        assertTrue(mapper.toPurl(component(null, "installer.bin", "1")).isEmpty());
    }

    @Test
    void aRawFileWithPackageLookingCoordinatesStillHasNoPurl() {
        // Nothing about a raw path makes it a package, however much it looks
        // like one. A pkg:generic purl here would look resolvable and never
        // resolve.
        assertTrue(mapper.toPurl(component("com/acme", "util-1.0.jar", "1")).isEmpty());
    }

    @Test
    void nullComponentYieldsEmpty() {
        assertTrue(mapper.toPurl(null).isEmpty());
    }

    private static ComponentEntity component(String namespace, String name, String version) {
        ComponentEntity component = new ComponentEntity();
        component.setFormat("raw");
        component.setNamespace(namespace);
        component.setName(name);
        component.setVersion(version);
        return component;
    }
}
