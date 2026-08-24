package de.bsnsoft.megarepo.repository.advisory.ghsa;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The package names produced here must be identical to what the format modules'
 * PurlMappers produce for a locally stored component — an advisory whose name differs by
 * one character is an advisory that never matches.
 */
class GhsaPackagesTest {

    @Test
    void mavenNameSplitsIntoGroupIdAndArtifactId() {
        GhsaPackages.Coordinates c =
                GhsaPackages.map("maven", "org.apache.logging.log4j:log4j-core").orElseThrow();

        assertEquals("maven", c.purlType());
        assertEquals("org.apache.logging.log4j", c.namespace());
        assertEquals("log4j-core", c.name());
    }

    @Test
    void mavenCaseIsPreserved() {
        GhsaPackages.Coordinates c = GhsaPackages.map("maven", "com.Acme:Widget-Core").orElseThrow();

        assertEquals("com.Acme", c.namespace());
        assertEquals("Widget-Core", c.name());
    }

    @Test
    void mavenWithoutGroupIdIsRejected() {
        // MavenPurlMapper refuses a component without a groupId for the same reason: a
        // bare artifactId is the ambiguous identity the purl migration removed.
        assertEquals(Optional.empty(), GhsaPackages.map("maven", "log4j-core"));
        assertEquals(Optional.empty(), GhsaPackages.map("maven", ":log4j-core"));
        assertEquals(Optional.empty(), GhsaPackages.map("maven", "org.apache:"));
    }

    @Test
    void npmScopeBecomesTheNamespaceIncludingTheAtSign() {
        GhsaPackages.Coordinates c = GhsaPackages.map("npm", "@babel/traverse").orElseThrow();

        assertEquals("npm", c.purlType());
        assertEquals("@babel", c.namespace());
        assertEquals("traverse", c.name());
    }

    @Test
    void npmUnscopedHasNoNamespaceAndKeepsItsCase() {
        GhsaPackages.Coordinates c = GhsaPackages.map("npm", "JSONStream").orElseThrow();

        assertNull(c.namespace());
        assertEquals("JSONStream", c.name());
    }

    @Test
    void npmScopeWithoutPackageNameIsRejected() {
        assertEquals(Optional.empty(), GhsaPackages.map("npm", "@babel"));
        assertEquals(Optional.empty(), GhsaPackages.map("npm", "@babel/"));
        assertEquals(Optional.empty(), GhsaPackages.map("npm", "@/traverse"));
    }

    @Test
    void pypiNameIsNormalisedPerPep503() {
        assertEquals("zope-interface", GhsaPackages.map("pip", "zope.interface").orElseThrow().name());
        assertEquals("flask-cors", GhsaPackages.map("pip", "Flask_Cors").orElseThrow().name());
        assertEquals("a-b", GhsaPackages.map("pip", "A._-.B").orElseThrow().name());

        GhsaPackages.Coordinates c = GhsaPackages.map("pip", "Django").orElseThrow();
        assertEquals("pypi", c.purlType());
        assertNull(c.namespace());
        assertEquals("django", c.name());
    }

    @Test
    void nugetIdIsLowercased() {
        GhsaPackages.Coordinates c = GhsaPackages.map("nuget", "Newtonsoft.Json").orElseThrow();

        assertEquals("nuget", c.purlType());
        assertNull(c.namespace());
        assertEquals("newtonsoft.json", c.name());
    }

    @Test
    void ecosystemNamesAreMatchedCaseInsensitively() {
        // REST publishes "maven", the GraphQL schema "MAVEN".
        assertEquals("maven", GhsaPackages.map("MAVEN", "com.acme:widget").orElseThrow().purlType());
        assertEquals("npm", GhsaPackages.map("NPM", "left-pad").orElseThrow().purlType());
        assertEquals("pypi", GhsaPackages.map("PIP", "requests").orElseThrow().purlType());
        assertEquals("nuget", GhsaPackages.map("NuGet", "Serilog").orElseThrow().purlType());
    }

    @Test
    void ecosystemsMegaRepoDoesNotHostAreDropped() {
        for (String ecosystem : new String[] {
            "go", "rubygems", "composer", "rust", "actions", "swift", "pub", "erlang", "other"
        }) {
            assertEquals(Optional.empty(), GhsaPackages.map(ecosystem, "whatever"), ecosystem);
            assertTrue(!GhsaPackages.supports(ecosystem), ecosystem);
        }
    }

    @Test
    void supportsSeparatesForeignEcosystemsFromUnusableNames() {
        assertTrue(GhsaPackages.supports("maven"));
        assertTrue(GhsaPackages.supports("PIP"));
        assertTrue(!GhsaPackages.supports("go"));
        assertTrue(!GhsaPackages.supports(null));
    }

    @Test
    void blankInputIsDropped() {
        assertEquals(Optional.empty(), GhsaPackages.map(null, "com.acme:widget"));
        assertEquals(Optional.empty(), GhsaPackages.map("maven", null));
        assertEquals(Optional.empty(), GhsaPackages.map("maven", "   "));
    }
}
