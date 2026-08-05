package de.bsnsoft.megarepo.format.pypi.firewall;

import com.github.packageurl.PackageURL;
import de.bsnsoft.megarepo.database.entity.ComponentEntity;
import de.bsnsoft.megarepo.format.pypi.naming.PythonNameNormalizer;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PypiPurlMapperTest {

    private final PypiPurlMapper mapper = new PypiPurlMapper(new PythonNameNormalizer());

    @Test
    void declaresPypi() {
        assertEquals("pypi", mapper.format());
        assertTrue(mapper.formatAliases().isEmpty());
    }

    // ------------------------------------------------------ PEP 503 normalising

    @Test
    void allSpellingsOfOneProjectCollapseOntoOneIdentity() {
        // PEP 503: lowercase, and runs of -, _ and . fold into a single -.
        // This is what stops the same project being tracked as three components.
        String expected = "pkg:pypi/zope-interface@5.4.0";

        assertEquals(expected, purl(component("zope.interface", "5.4.0")).canonicalize());
        assertEquals(expected, purl(component("zope_interface", "5.4.0")).canonicalize());
        assertEquals(expected, purl(component("Zope-Interface", "5.4.0")).canonicalize());
        assertEquals(expected, purl(component("ZOPE.INTERFACE", "5.4.0")).canonicalize());
    }

    @Test
    void dotsAreNormalisedEvenThoughTheLibraryLeavesThemAlone() {
        // packageurl-java only lowercases and maps _ to - for the pypi type; a
        // dotted name would otherwise survive as a second, non-matching identity.
        assertEquals("pkg:pypi/ruamel-yaml@0.18.6",
                purl(component("ruamel.yaml", "0.18.6")).canonicalize());
    }

    @Test
    void runsOfSeparatorsCollapseToASingleHyphen() {
        assertEquals("pkg:pypi/foo-bar@1.0", purl(component("foo__bar", "1.0")).canonicalize());
        assertEquals("pkg:pypi/foo-bar@1.0", purl(component("foo-._-bar", "1.0")).canonicalize());
    }

    @Test
    void realWorldCoordinates() {
        assertEquals("pkg:pypi/django@4.2.11", purl(component("Django", "4.2.11")).canonicalize());
        assertEquals("pkg:pypi/requests@2.31.0", purl(component("requests", "2.31.0")).canonicalize());
        assertEquals("pkg:pypi/pillow@10.3.0", purl(component("Pillow", "10.3.0")).canonicalize());
        assertEquals("pkg:pypi/typing-extensions@4.12.2",
                purl(component("typing_extensions", "4.12.2")).canonicalize());
    }

    @Test
    void pypiHasNoNamespace() {
        assertNull(purl(component("requests", "2.31.0")).getNamespace());
    }

    @Test
    void pep440VersionIsPassedThroughUnchanged() {
        // Version semantics are not identity's business.
        assertEquals("pkg:pypi/urllib3@2.0.0rc1", purl(component("urllib3", "2.0.0rc1")).canonicalize());
        assertEquals("pkg:pypi/numpy@1.26.4.post1", purl(component("numpy", "1.26.4.post1")).canonicalize());
    }

    // ------------------------------------------------------------- empty cases

    @Test
    void withoutANameThereIsNoPurl() {
        assertTrue(mapper.toPurl(component(null, "1.0")).isEmpty());
        assertTrue(mapper.toPurl(component("   ", "1.0")).isEmpty());
    }

    @Test
    void nullComponentYieldsEmpty() {
        assertTrue(mapper.toPurl(null).isEmpty());
    }

    @Test
    void missingVersionStillYieldsAVersionlessPurl() {
        assertEquals("pkg:pypi/requests", purl(component("requests", null)).canonicalize());
    }

    // ------------------------------------------------------------------ helpers

    private PackageURL purl(ComponentEntity component) {
        Optional<PackageURL> result = mapper.toPurl(component);
        assertTrue(result.isPresent(), "expected a purl for " + component.getName());
        return result.get();
    }

    private static ComponentEntity component(String name, String version) {
        ComponentEntity component = new ComponentEntity();
        component.setFormat("pypi");
        component.setNamespace(null);
        component.setName(name);
        component.setVersion(version);
        return component;
    }
}
