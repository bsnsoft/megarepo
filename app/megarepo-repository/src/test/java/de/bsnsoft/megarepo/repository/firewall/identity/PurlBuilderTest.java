package de.bsnsoft.megarepo.repository.firewall.identity;

import com.github.packageurl.PackageURL;
import de.bsnsoft.megarepo.database.entity.ComponentEntity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mapper collection, format dispatch and the identity fallback chain.
 * No format module is on this module's classpath, so the mappers here are stubs.
 */
class PurlBuilderTest {

    // ---------------------------------------------------------------- dispatch

    @Test
    void dispatchesOnCanonicalFormatKey() {
        PurlBuilder builder = new PurlBuilder(List.of(new StubMapper("maven2", Set.of("maven"))));

        Optional<PackageURL> purl = builder.toPurl(component("maven2", "com.acme", "util", "1.0"));

        assertTrue(purl.isPresent());
        assertEquals("pkg:maven/com.acme/util@1.0", purl.get().canonicalize());
    }

    @Test
    void dispatchesOnFormatAlias() {
        // Repository rows from older configs carry "maven"; the proxy caching
        // path copies that value verbatim onto the component.
        PurlBuilder builder = new PurlBuilder(List.of(new StubMapper("maven2", Set.of("maven"))));

        assertTrue(builder.toPurl(component("maven", "com.acme", "util", "1.0")).isPresent());
    }

    @Test
    void formatKeyIsCaseInsensitive() {
        PurlBuilder builder = new PurlBuilder(List.of(new StubMapper("maven2", Set.of())));

        assertTrue(builder.toPurl(component("MAVEN2", "com.acme", "util", "1.0")).isPresent());
    }

    @Test
    void aliasNeverShadowsAnotherMappersCanonicalKey() {
        // "npm" is npm's canonical key and must win, even though the stub
        // registered before it claims "npm" as an alias.
        StubMapper impostor = new StubMapper("maven2", Set.of("npm"));
        StubMapper npm = new StubMapper("npm", Set.of());

        PurlBuilder builder = new PurlBuilder(List.of(impostor, npm));

        assertTrue(builder.toPurl(component("npm", "ns", "name", "1.0")).isPresent());
        assertEquals("pkg:npm/ns/name@1.0",
                builder.toPurl(component("npm", "ns", "name", "1.0")).orElseThrow().canonicalize());
    }

    @Test
    void unknownFormatYieldsNoPurl() {
        PurlBuilder builder = new PurlBuilder(List.of(new StubMapper("maven2", Set.of())));

        assertTrue(builder.toPurl(component("cargo", "ns", "name", "1.0")).isEmpty());
    }

    @Test
    void nullAndBlankInputsAreTolerated() {
        PurlBuilder builder = new PurlBuilder(List.of(new StubMapper("maven2", Set.of())));

        assertTrue(builder.toPurl(null).isEmpty());
        assertTrue(builder.toPurl(component(null, "ns", "name", "1.0")).isEmpty());
        assertTrue(builder.toPurl(component("   ", "ns", "name", "1.0")).isEmpty());
    }

    @Test
    void mapperThatThrowsDegradesToHashIdentityInsteadOfFailingTheRequest() {
        PurlBuilder builder = new PurlBuilder(List.of(new PurlMapper() {
            @Override
            public String format() {
                return "maven2";
            }

            @Override
            public Optional<PackageURL> toPurl(ComponentEntity component) {
                throw new IllegalStateException("boom");
            }
        }));

        ComponentEntity component = component("maven2", "com.acme", "util", "1.0");

        assertTrue(builder.toPurl(component).isEmpty());
        assertEquals("sha256:abc123",
                builder.identify(component, "ABC123").key());
    }

    @Test
    void mapperWithBlankFormatKeyIsIgnored() {
        PurlBuilder builder = new PurlBuilder(List.of(new StubMapper("  ", Set.of())));

        assertTrue(builder.supportedFormatKeys().isEmpty());
    }

    @Test
    void supportedFormatKeysIncludeAliases() {
        PurlBuilder builder = new PurlBuilder(List.of(new StubMapper("maven2", Set.of("maven"))));

        assertEquals(Set.of("maven2", "maven"), builder.supportedFormatKeys());
    }

    // ------------------------------------------------------- identity fallback

    @Test
    void purlIdentityWinsOverHashWhenBothAreAvailable() {
        PurlBuilder builder = new PurlBuilder(List.of(new StubMapper("maven2", Set.of())));

        ComponentIdentity identity =
                builder.identify(component("maven2", "com.acme", "util", "1.0"), "deadbeef");

        assertInstanceOf(ComponentIdentity.Purl.class, identity);
        assertEquals("pkg:maven/com.acme/util@1.0", identity.key());
        assertTrue(identity.isResolvable());
    }

    @Test
    void formatWithoutPurlFallsBackToHashIdentity() {
        // Stands in for raw and docker, whose mappers always return empty.
        PurlBuilder builder = new PurlBuilder(List.of(new EmptyMapper("raw")));

        ComponentIdentity identity =
                builder.identify(component("raw", "files/2024", "report.pdf", "1"), "E3B0C442");

        assertInstanceOf(ComponentIdentity.Hash.class, identity);
        assertEquals("sha256:e3b0c442", identity.key());
        assertFalse(identity.isResolvable(), "a hash cannot be looked up in an advisory source");
    }

    @Test
    void withoutPurlAndWithoutDigestTheComponentIsUnidentified() {
        PurlBuilder builder = new PurlBuilder(List.of(new EmptyMapper("raw")));

        ComponentIdentity identity = builder.identify(component("raw", "files", "report.pdf", "1"));

        assertInstanceOf(ComponentIdentity.Unidentified.class, identity);
        assertEquals("unidentified:raw/files/report.pdf@1", identity.key());
        assertFalse(identity.isResolvable());
    }

    @Test
    void blankDigestIsTreatedAsNoDigest() {
        PurlBuilder builder = new PurlBuilder(List.of(new EmptyMapper("raw")));

        assertInstanceOf(ComponentIdentity.Unidentified.class,
                builder.identify(component("raw", "files", "report.pdf", "1"), "   "));
    }

    @Test
    void nullComponentIsUnidentifiedRatherThanAnException() {
        PurlBuilder builder = new PurlBuilder(List.of());

        assertInstanceOf(ComponentIdentity.Unidentified.class, builder.identify(null));
    }

    // ----------------------------------------------------------- identity keys

    @Test
    void identityKeysOfDifferentKindsNeverCollide() {
        PurlBuilder builder = new PurlBuilder(List.of(
                new StubMapper("maven2", Set.of()), new EmptyMapper("raw")));

        String purlKey = builder.identify(component("maven2", "com.acme", "util", "1.0")).key();
        String hashKey = builder.identify(component("raw", "f", "x", "1"), "abc").key();
        String unknownKey = builder.identify(component("raw", "f", "x", "1")).key();

        assertTrue(purlKey.startsWith("pkg:"));
        assertTrue(hashKey.startsWith("sha256:"));
        assertTrue(unknownKey.startsWith("unidentified:"));
        assertNotEquals(purlKey, hashKey);
        assertNotEquals(hashKey, unknownKey);
    }

    @Test
    void purlIdentityExposesQualifierFreeCoordinatesForAdvisoryMatching() throws Exception {
        // Advisory feeds publish qualifier-free purls, so a sources jar must
        // still match the advisory for its package while keeping a distinct key.
        var withClassifier = new ComponentIdentity.Purl(new PackageURL(
                "pkg:maven/com.acme/util@1.0?classifier=sources"));
        var plain = new ComponentIdentity.Purl(new PackageURL("pkg:maven/com.acme/util@1.0"));

        assertNotEquals(plain.key(), withClassifier.key());
        assertEquals(plain.coordinates(), withClassifier.coordinates());
        assertEquals("pkg:maven/com.acme/util@1.0", withClassifier.coordinates());
    }

    @Test
    void hashIdentityNormalisesAlgorithmAndDigestToLowercase() {
        assertEquals("sha256:abcdef", new ComponentIdentity.Hash("SHA256", "ABCDEF").key());
    }

    // ------------------------------------------------------------------ helpers

    private static ComponentEntity component(String format, String namespace, String name, String version) {
        ComponentEntity component = new ComponentEntity();
        component.setFormat(format);
        component.setNamespace(namespace);
        component.setName(name);
        component.setVersion(version);
        return component;
    }

    /** Maps whatever it is given onto a purl whose type is the mapper's canonical format. */
    private record StubMapper(String format, Set<String> formatAliases) implements PurlMapper {
        @Override
        public Optional<PackageURL> toPurl(ComponentEntity component) {
            try {
                String type = "maven2".equals(format) ? "maven" : format;
                return Optional.of(new PackageURL(type, component.getNamespace(),
                        component.getName(), component.getVersion(), null, null));
            } catch (Exception e) {
                return Optional.empty();
            }
        }
    }

    /** Stands in for the raw and docker mappers. */
    private record EmptyMapper(String format) implements PurlMapper {
        @Override
        public Optional<PackageURL> toPurl(ComponentEntity component) {
            return Optional.empty();
        }
    }
}
