package de.bsnsoft.megarepo.format.npm.firewall;

import com.github.packageurl.PackageURL;
import de.bsnsoft.megarepo.database.entity.ComponentEntity;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NpmPurlMapperTest {

    private final NpmPurlMapper mapper = new NpmPurlMapper();

    @Test
    void declaresNpm() {
        assertEquals("npm", mapper.format());
        assertTrue(mapper.formatAliases().isEmpty());
    }

    // ------------------------------------------------- the customer's evidence

    @Test
    void sameNameUnderDifferentScopesProducesDifferentPurls() {
        // CPE guessing sees only "core" for both of these.
        PackageURL acme = purl(component("@acme", "core", "1.0.0"));
        PackageURL other = purl(component("@other", "core", "1.0.0"));

        assertEquals("pkg:npm/%40acme/core@1.0.0", acme.canonicalize());
        assertEquals("pkg:npm/%40other/core@1.0.0", other.canonicalize());
        assertNotEquals(acme.canonicalize(), other.canonicalize());
        assertFalse(acme.isCoordinatesEquals(other));
    }

    @Test
    void aScopedPackageIsNotTheSameAsTheUnscopedOneOfTheSameName() {
        PackageURL scoped = purl(component("@types", "node", "20.11.0"));
        PackageURL unscoped = purl(component(null, "node", "20.11.0"));

        assertEquals("pkg:npm/%40types/node@20.11.0", scoped.canonicalize());
        assertEquals("pkg:npm/node@20.11.0", unscoped.canonicalize());
        assertFalse(scoped.isCoordinatesEquals(unscoped));
    }

    // --------------------------------------------------------- scope handling

    @Test
    void scopeIsKeptWithItsAtSignAndPercentEncodedInTheCanonicalForm() {
        assertEquals("pkg:npm/%40angular/animations@17.3.0",
                purl(component("@angular", "animations", "17.3.0")).canonicalize());
        assertEquals("@angular", purl(component("@angular", "animations", "17.3.0")).getNamespace());
    }

    @Test
    void unscopedPackage() {
        assertEquals("pkg:npm/lodash@4.17.21", purl(component(null, "lodash", "4.17.21")).canonicalize());
        assertEquals("pkg:npm/express@4.19.2", purl(component(null, "express", "4.19.2")).canonicalize());
    }

    @Test
    void aScopeStoredWithoutItsAtSignIsRepaired() {
        assertEquals("pkg:npm/%40angular/animations@17.3.0",
                purl(component("angular", "animations", "17.3.0")).canonicalize());
    }

    @Test
    void aNameThatStillCarriesItsScopeIsSplitInsteadOfEncodedWhole() {
        // Guards against pkg:npm/%40angular%2Fanimations, which would never
        // match an advisory.
        PackageURL result = purl(component(null, "@angular/animations", "17.3.0"));

        assertEquals("@angular", result.getNamespace());
        assertEquals("animations", result.getName());
        assertEquals("pkg:npm/%40angular/animations@17.3.0", result.canonicalize());
    }

    @Test
    void anExplicitNamespaceWinsOverAScopeLookingName() {
        // Only split when there is no namespace — never second-guess stored data.
        assertEquals("@acme", purl(component("@acme", "core", "1.0.0")).getNamespace());
    }

    @Test
    void legacyMixedCaseNameIsPreserved() {
        // The npm registry serves JSONStream case-sensitively and OSV/GHSA
        // publish it that way; lowercasing would break advisory matching.
        assertEquals("pkg:npm/JSONStream@1.3.5", purl(component(null, "JSONStream", "1.3.5")).canonicalize());
    }

    @Test
    void prereleaseVersionIsPassedThrough() {
        assertEquals("pkg:npm/typescript@5.5.0-beta",
                purl(component(null, "typescript", "5.5.0-beta")).canonicalize());
    }

    // ------------------------------------------------------------- empty cases

    @Test
    void withoutANameThereIsNoPurl() {
        assertTrue(mapper.toPurl(component("@acme", null, "1.0.0")).isEmpty());
        assertTrue(mapper.toPurl(component("@acme", "   ", "1.0.0")).isEmpty());
    }

    @Test
    void aScopeWithoutAPackageNameYieldsNoPurl() {
        assertTrue(mapper.toPurl(component(null, "@angular/", "17.3.0")).isEmpty());
    }

    @Test
    void nullComponentYieldsEmpty() {
        assertTrue(mapper.toPurl(null).isEmpty());
    }

    @Test
    void missingVersionStillYieldsAVersionlessPurl() {
        assertEquals("pkg:npm/%40angular/animations",
                purl(component("@angular", "animations", null)).canonicalize());
    }

    // ------------------------------------------------------------------ helpers

    private PackageURL purl(ComponentEntity component) {
        Optional<PackageURL> result = mapper.toPurl(component);
        assertTrue(result.isPresent(), "expected a purl for " + component.getNamespace()
                + "/" + component.getName() + "@" + component.getVersion());
        return result.get();
    }

    private static ComponentEntity component(String namespace, String name, String version) {
        ComponentEntity component = new ComponentEntity();
        component.setFormat("npm");
        component.setNamespace(namespace);
        component.setName(name);
        component.setVersion(version);
        return component;
    }
}
