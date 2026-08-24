package de.bsnsoft.megarepo.repository.advisory.osv;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The purl side of the mapping. Every assertion here has a counterpart in a format
 * module's {@code PurlMapper}: if the two ever disagree, advisories stop matching stored
 * components and the firewall goes quiet without anything looking broken.
 */
class OsvEcosystemTest {

    @Test
    void mapsTheFourMirroredEcosystems() {
        assertEquals(Optional.of(OsvEcosystem.MAVEN), OsvEcosystem.fromOsvName("Maven"));
        assertEquals(Optional.of(OsvEcosystem.NPM), OsvEcosystem.fromOsvName("npm"));
        assertEquals(Optional.of(OsvEcosystem.PYPI), OsvEcosystem.fromOsvName("PyPI"));
        assertEquals(Optional.of(OsvEcosystem.NUGET), OsvEcosystem.fromOsvName("NuGet"));

        assertEquals("maven", OsvEcosystem.MAVEN.purlType());
        assertEquals("npm", OsvEcosystem.NPM.purlType());
        assertEquals("pypi", OsvEcosystem.PYPI.purlType());
        assertEquals("nuget", OsvEcosystem.NUGET.purlType());
    }

    @Test
    void stripsTheEcosystemQualifier() {
        assertEquals(
                Optional.of(OsvEcosystem.MAVEN),
                OsvEcosystem.fromOsvName("Maven:https://maven.google.com"));
        assertEquals(Optional.of(OsvEcosystem.PYPI), OsvEcosystem.fromOsvName("pypi"));
    }

    @Test
    void rejectsEcosystemsMegaRepoDoesNotHost() {
        assertTrue(OsvEcosystem.fromOsvName("Go").isEmpty());
        assertTrue(OsvEcosystem.fromOsvName("Debian:12").isEmpty());
        assertTrue(OsvEcosystem.fromOsvName("crates.io").isEmpty());
        assertTrue(OsvEcosystem.fromOsvName("").isEmpty());
        assertTrue(OsvEcosystem.fromOsvName(null).isEmpty());
    }

    @Test
    void splitsMavenCoordinatesIntoGroupAndArtifact() {
        OsvEcosystem.PurlName name = OsvEcosystem.MAVEN
                .splitPackageName("org.apache.logging.log4j:log4j-core")
                .orElseThrow();
        assertEquals("org.apache.logging.log4j", name.namespace());
        assertEquals("log4j-core", name.name());
    }

    @Test
    void rejectsMavenNamesWithoutAGroupId() {
        // A bare artifactId is the CPE-style ambiguity purl identity exists to remove.
        assertTrue(OsvEcosystem.MAVEN.splitPackageName("log4j-core").isEmpty());
        assertTrue(OsvEcosystem.MAVEN.splitPackageName(":log4j-core").isEmpty());
        assertTrue(OsvEcosystem.MAVEN.splitPackageName("org.acme:").isEmpty());
        assertTrue(OsvEcosystem.MAVEN.splitPackageName("a:b:c").isEmpty());
    }

    @Test
    void keepsTheAtSignWithTheNpmScope() {
        OsvEcosystem.PurlName scoped =
                OsvEcosystem.NPM.splitPackageName("@evilcorp/postinstall-helper").orElseThrow();
        assertEquals("@evilcorp", scoped.namespace());
        assertEquals("postinstall-helper", scoped.name());

        OsvEcosystem.PurlName plain = OsvEcosystem.NPM.splitPackageName("JSONStream").orElseThrow();
        assertNull(plain.namespace());
        assertEquals("JSONStream", plain.name(), "npm casing must survive — GHSA publishes it");

        assertTrue(OsvEcosystem.NPM.splitPackageName("scope/name").isEmpty());
        assertTrue(OsvEcosystem.NPM.splitPackageName("@scope/").isEmpty());
    }

    @Test
    void normalisesPyPiNamesPerPep503() {
        assertEquals("zope-interface", OsvEcosystem.PYPI.splitPackageName("Zope.Interface").orElseThrow().name());
        assertEquals("zope-interface", OsvEcosystem.PYPI.splitPackageName("zope_interface").orElseThrow().name());
        assertEquals("zope-interface", OsvEcosystem.PYPI.splitPackageName("ZOPE--INTERFACE").orElseThrow().name());
        assertNull(OsvEcosystem.PYPI.splitPackageName("Django").orElseThrow().namespace());
    }

    @Test
    void lowercasesNuGetIds() {
        assertEquals("newtonsoft.json", OsvEcosystem.NUGET.splitPackageName("Newtonsoft.Json").orElseThrow().name());
        assertNull(OsvEcosystem.NUGET.splitPackageName("Newtonsoft.Json").orElseThrow().namespace());
    }

    @Test
    void rejectsBlankPackageNames() {
        for (OsvEcosystem ecosystem : OsvEcosystem.values()) {
            assertTrue(ecosystem.splitPackageName(null).isEmpty());
            assertTrue(ecosystem.splitPackageName("   ").isEmpty());
        }
    }
}
