package de.bsnsoft.megarepo.repository.firewall.identity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionSchemesTest {

    @ParameterizedTest(name = "purl type {0} -> {1}")
    @CsvSource({
        "maven, maven",
        "npm, semver",
        "pypi, pep440",
        "nuget, nuget",
        "docker, generic",
        "generic, generic",
    })
    void mapsEveryPurlTypeToItsScheme(String purlType, String expectedSchemeId) {
        assertEquals(expectedSchemeId, VersionSchemes.forPurlType(purlType).id());
        assertTrue(VersionSchemes.isKnownPurlType(purlType));
    }

    /**
     * OSV names its ecosystems {@code Maven}, {@code PyPI}, {@code NuGet}. Those
     * feed straight into the same lookup, so the key is normalised rather than
     * requiring every caller to remember to lower-case.
     */
    @ParameterizedTest(name = "{0} resolves like its canonical purl type")
    @CsvSource({
        "Maven, maven",
        "PyPI, pep440",
        "NuGet, nuget",
        "NPM, semver",
        "'  maven  ', maven",
    })
    void lookupIsCaseAndWhitespaceInsensitive(String purlType, String expectedSchemeId) {
        assertEquals(expectedSchemeId, VersionSchemes.forPurlType(purlType).id());
    }

    /**
     * A firewall that cannot name the ecosystem still has to reach a decision,
     * so an unknown type degrades to the generic scheme instead of throwing on
     * the download path.
     */
    @ParameterizedTest(name = "unknown type {0} falls back to generic")
    @ValueSource(strings = {"cargo", "golang", "gem", "swift", "conan", "totally-made-up"})
    void unknownPurlTypeFallsBackToGeneric(String purlType) {
        assertSame(VersionSchemes.GENERIC, VersionSchemes.forPurlType(purlType));
        assertFalse(VersionSchemes.isKnownPurlType(purlType));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void missingPurlTypeFallsBackToGeneric(String purlType) {
        assertSame(VersionSchemes.GENERIC, VersionSchemes.forPurlType(purlType));
        assertFalse(VersionSchemes.isKnownPurlType(purlType));
    }

    /** The schemes are stateless singletons; repeated lookups must not allocate. */
    @Test
    void lookupReturnsSharedInstances() {
        assertSame(VersionSchemes.forPurlType("maven"), VersionSchemes.forPurlType("maven"));
        assertSame(VersionSchemes.MAVEN, VersionSchemes.forPurlType("maven"));
        assertSame(VersionSchemes.SEMVER, VersionSchemes.forPurlType("npm"));
        assertSame(VersionSchemes.PEP440, VersionSchemes.forPurlType("pypi"));
        assertSame(VersionSchemes.NUGET, VersionSchemes.forPurlType("nuget"));
    }

    /**
     * The point of the registry: the same version string means different things
     * per ecosystem, so picking the wrong scheme changes the answer. If these
     * ever agree, the schemes have collapsed into one.
     */
    @Test
    void schemeChoiceChangesTheAnswer() {
        // 1!1.0 — a PEP 440 epoch outranks the release segment entirely, so it
        // beats 2.0. Nowhere else does "1!1" mean anything.
        assertTrue(VersionSchemes.forPurlType("pypi").compare("1!1.0", "2.0") > 0);
        assertTrue(VersionSchemes.forPurlType("maven").compare("1!1.0", "2.0") < 0);

        // 1.0-sp1 — a Maven service pack ranks above the plain release; every
        // other ecosystem reads the suffix as a pre-release or as noise.
        assertTrue(VersionSchemes.forPurlType("maven").compare("1.0-sp1", "1.0") > 0);
        assertTrue(VersionSchemes.forPurlType("pypi").compare("1.0-sp1", "1.0") < 0);
        assertTrue(VersionSchemes.forPurlType("nuget").compare("1.0-sp1", "1.0") < 0);

        // 1.0.0-Beta — the same version as -beta for NuGet, a different one for
        // npm, which compares pre-release identifiers case-sensitively.
        assertEquals(0, VersionSchemes.forPurlType("nuget").compare("1.0.0-Beta", "1.0.0-beta"));
        assertNotEquals(0, VersionSchemes.forPurlType("npm").compare("1.0.0-Beta", "1.0.0-beta"));

        // 1.0.0+build — build metadata is excluded from precedence by semver
        // and NuGet, but Maven and PEP 440 have no such rule.
        assertEquals(0, VersionSchemes.forPurlType("npm").compare("1.0.0+build", "1.0.0"));
        assertEquals(0, VersionSchemes.forPurlType("nuget").compare("1.0.0+build", "1.0.0"));
        assertTrue(VersionSchemes.forPurlType("maven").compare("1.0.0+build", "1.0.0") > 0);

        // 1.0.0-rc1 — every real ecosystem ranks an rc below its release; the
        // generic fallback deliberately does not, because nothing identifies
        // the suffix as a pre-release.
        assertTrue(VersionSchemes.forPurlType("npm").compare("1.0.0-rc1", "1.0.0") < 0);
        assertTrue(VersionSchemes.forPurlType("docker").compare("1.0.0-rc1", "1.0.0") > 0);
    }

    @Test
    void everySchemeHasADistinctId() {
        assertEquals(
                5,
                java.util.Set.of(
                                VersionSchemes.MAVEN.id(),
                                VersionSchemes.SEMVER.id(),
                                VersionSchemes.PEP440.id(),
                                VersionSchemes.NUGET.id(),
                                VersionSchemes.GENERIC.id())
                        .size());
    }
}
