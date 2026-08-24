package de.bsnsoft.megarepo.format.maven.firewall;

import com.github.packageurl.PackageURL;
import de.bsnsoft.megarepo.database.entity.ComponentEntity;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MavenPurlMapperTest {

    private final MavenPurlMapper mapper = new MavenPurlMapper();

    @Test
    void declaresMaven2AndTheLegacyMavenAlias() {
        assertEquals("maven2", mapper.format());
        assertEquals(Set.of("maven"), mapper.formatAliases());
    }

    // ------------------------------------------------- the customer's evidence

    @Test
    void sameArtifactIdUnderDifferentGroupIdsProducesDifferentPurls() {
        // This is exactly the case the CPE guessing collapses: it derives its
        // product candidates from the artifact name alone, so both of these
        // match the CPE product "util". With purl identity the groupId is part
        // of the identity and the two cannot be confused.
        PackageURL acme = purl(component("com.acme", "util", "1.0"));
        PackageURL other = purl(component("org.other", "util", "1.0"));

        assertEquals("pkg:maven/com.acme/util@1.0", acme.canonicalize());
        assertEquals("pkg:maven/org.other/util@1.0", other.canonicalize());
        assertNotEquals(acme.canonicalize(), other.canonicalize());
        assertFalse(acme.isCoordinatesEquals(other));
    }

    @Test
    void siblingArtifactsOfTheSameProjectStayDistinct() {
        // The CPE rule "first segment before dash" folds log4j-api onto the
        // product "log4j" and hands it log4j-core's CVEs.
        PackageURL api = purl(component("org.apache.logging.log4j", "log4j-api", "2.17.1"));
        PackageURL core = purl(component("org.apache.logging.log4j", "log4j-core", "2.14.1"));

        assertEquals("pkg:maven/org.apache.logging.log4j/log4j-api@2.17.1", api.canonicalize());
        assertEquals("pkg:maven/org.apache.logging.log4j/log4j-core@2.14.1", core.canonicalize());
        assertFalse(api.isCoordinatesEquals(core));
    }

    // -------------------------------------------------------- realistic coords

    @Test
    void realWorldCoordinates() {
        assertEquals("pkg:maven/org.apache.commons/commons-lang3@3.14.0",
                purl(component("org.apache.commons", "commons-lang3", "3.14.0")).canonicalize());
        assertEquals("pkg:maven/com.fasterxml.jackson.core/jackson-databind@2.18.6",
                purl(component("com.fasterxml.jackson.core", "jackson-databind", "2.18.6")).canonicalize());
        assertEquals("pkg:maven/org.springframework/spring-web@6.2.1",
                purl(component("org.springframework", "spring-web", "6.2.1")).canonicalize());
    }

    @Test
    void groupIdKeepsItsDotsAndIsNotLowercased() {
        // Maven coordinates are case-sensitive; the extractor already stores the
        // groupId dotted, which is what the purl namespace wants.
        assertEquals("pkg:maven/com.ACME.Tools/Util@1.0-SNAPSHOT",
                purl(component("com.ACME.Tools", "Util", "1.0-SNAPSHOT")).canonicalize());
    }

    @Test
    void snapshotVersionIsPassedThroughUntouched() {
        assertEquals("pkg:maven/com.acme/util@1.0-20240101.120000-3",
                purl(component("com.acme", "util", "1.0-20240101.120000-3")).canonicalize());
    }

    // ------------------------------------------------------------- qualifiers

    @Test
    void classifierAndExtensionBecomeQualifiersWhenPresent() {
        ComponentEntity component = component("com.acme", "util", "1.0");
        component.setAttributes(attributes("classifier", "sources", "extension", "jar"));

        assertEquals("pkg:maven/com.acme/util@1.0?classifier=sources&type=jar",
                purl(component).canonicalize());
    }

    @Test
    void qualifiedArtifactStillShareItsAdvisoryCoordinates() {
        // Advisory feeds publish qualifier-free Maven purls, so the sources jar
        // must resolve to the same coordinates as the main jar.
        ComponentEntity sources = component("com.acme", "util", "1.0");
        sources.setAttributes(attributes("classifier", "sources", "extension", "jar"));

        assertEquals("pkg:maven/com.acme/util@1.0", purl(sources).getCoordinates());
        assertTrue(purl(sources).isCoordinatesEquals(purl(component("com.acme", "util", "1.0"))));
    }

    @Test
    void emptyAttributeValuesAreNotTurnedIntoQualifiers() {
        // MavenCoordinateExtractor writes "" for an absent classifier, and a
        // purl rejects an empty qualifier value outright.
        ComponentEntity component = component("com.acme", "util", "1.0");
        component.setAttributes(attributes("classifier", "", "extension", "jar"));

        assertEquals("pkg:maven/com.acme/util@1.0?type=jar", purl(component).canonicalize());
    }

    @Test
    void nonStringAttributeValuesAreIgnored() {
        ComponentEntity component = component("com.acme", "util", "1.0");
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("classifier", 42);
        component.setAttributes(attributes);

        assertEquals("pkg:maven/com.acme/util@1.0", purl(component).canonicalize());
    }

    // ------------------------------------------------------------- empty cases

    @Test
    void withoutAGroupIdThereIsNoPurl() {
        // pkg:maven requires a namespace, and a bare artifactId is precisely the
        // ambiguous identity this change exists to remove.
        assertTrue(mapper.toPurl(component(null, "util", "1.0")).isEmpty());
        assertTrue(mapper.toPurl(component("   ", "util", "1.0")).isEmpty());
    }

    @Test
    void withoutAnArtifactIdThereIsNoPurl() {
        assertTrue(mapper.toPurl(component("com.acme", null, "1.0")).isEmpty());
        assertTrue(mapper.toPurl(component("com.acme", "  ", "1.0")).isEmpty());
    }

    @Test
    void nullComponentYieldsEmpty() {
        assertTrue(mapper.toPurl(null).isEmpty());
    }

    @Test
    void missingVersionStillYieldsAVersionlessPurl() {
        // Useful for component-scoped exemptions, which apply to all versions.
        assertEquals("pkg:maven/com.acme/util", purl(component("com.acme", "util", null)).canonicalize());
    }

    @Test
    void nullAttributeMapIsTolerated() {
        ComponentEntity component = component("com.acme", "util", "1.0");
        component.setAttributes(null);

        assertEquals("pkg:maven/com.acme/util@1.0", purl(component).canonicalize());
    }

    // ------------------------------------------------------------------ helpers

    private PackageURL purl(ComponentEntity component) {
        Optional<PackageURL> result = mapper.toPurl(component);
        assertTrue(result.isPresent(), "expected a purl for " + component.getNamespace()
                + ":" + component.getName() + ":" + component.getVersion());
        return result.get();
    }

    private static ComponentEntity component(String namespace, String name, String version) {
        ComponentEntity component = new ComponentEntity();
        component.setFormat("maven2");
        component.setNamespace(namespace);
        component.setName(name);
        component.setVersion(version);
        return component;
    }

    private static Map<String, Object> attributes(String... keyValues) {
        Map<String, Object> attributes = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            attributes.put(keyValues[i], keyValues[i + 1]);
        }
        return attributes;
    }
}
