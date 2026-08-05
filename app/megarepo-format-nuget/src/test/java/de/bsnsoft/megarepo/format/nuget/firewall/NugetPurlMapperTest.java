package de.bsnsoft.megarepo.format.nuget.firewall;

import com.github.packageurl.PackageURL;
import de.bsnsoft.megarepo.database.entity.ComponentEntity;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NugetPurlMapperTest {

    private final NugetPurlMapper mapper = new NugetPurlMapper();

    @Test
    void declaresNuget() {
        assertEquals("nuget", mapper.format());
        assertTrue(mapper.formatAliases().isEmpty());
    }

    // -------------------------------------------------- case-insensitive ids

    @Test
    void everyCasingOfAnIdCollapsesOntoOneIdentity() {
        // NuGet ids are case-insensitive and the V3 protocol lowercases them on
        // every URL, so a hosted push and a proxy-cached copy of the same
        // package must not end up as two identities.
        String expected = "pkg:nuget/newtonsoft.json@13.0.3";

        assertEquals(expected, purl(component("Newtonsoft.Json", "13.0.3")).canonicalize());
        assertEquals(expected, purl(component("newtonsoft.json", "13.0.3")).canonicalize());
        assertEquals(expected, purl(component("NEWTONSOFT.JSON", "13.0.3")).canonicalize());
    }

    @Test
    void realWorldCoordinates() {
        assertEquals("pkg:nuget/system.text.json@8.0.4",
                purl(component("System.Text.Json", "8.0.4")).canonicalize());
        assertEquals("pkg:nuget/serilog.sinks.console@6.0.0",
                purl(component("Serilog.Sinks.Console", "6.0.0")).canonicalize());
        assertEquals("pkg:nuget/microsoft.data.sqlclient@5.2.2",
                purl(component("Microsoft.Data.SqlClient", "5.2.2")).canonicalize());
    }

    @Test
    void nugetHasNoNamespace() {
        assertNull(purl(component("Newtonsoft.Json", "13.0.3")).getNamespace());
    }

    @Test
    void dotsInTheIdAreNotSeparatorsAndStayIntact() {
        // Unlike PyPI, a NuGet id's dots are part of the name.
        assertEquals("system.text.json", purl(component("System.Text.Json", "8.0.4")).getName());
    }

    @Test
    void versionIsPassedThroughUnchanged() {
        // Version normalisation is version semantics, not identity.
        assertEquals("pkg:nuget/serilog@4.0.0-dev-02226",
                purl(component("Serilog", "4.0.0-dev-02226")).canonicalize());
        assertEquals("pkg:nuget/acme.lib@1.2.3.4", purl(component("Acme.Lib", "1.2.3.4")).canonicalize());
    }

    @Test
    void surroundingWhitespaceInTheIdIsTrimmed() {
        assertEquals("pkg:nuget/newtonsoft.json@13.0.3",
                purl(component("  Newtonsoft.Json  ", "13.0.3")).canonicalize());
    }

    // ------------------------------------------------------------- empty cases

    @Test
    void withoutAnIdThereIsNoPurl() {
        assertTrue(mapper.toPurl(component(null, "13.0.3")).isEmpty());
        assertTrue(mapper.toPurl(component("   ", "13.0.3")).isEmpty());
    }

    @Test
    void nullComponentYieldsEmpty() {
        assertTrue(mapper.toPurl(null).isEmpty());
    }

    @Test
    void missingVersionStillYieldsAVersionlessPurl() {
        assertEquals("pkg:nuget/newtonsoft.json", purl(component("Newtonsoft.Json", null)).canonicalize());
    }

    // ------------------------------------------------------------------ helpers

    private PackageURL purl(ComponentEntity component) {
        Optional<PackageURL> result = mapper.toPurl(component);
        assertTrue(result.isPresent(), "expected a purl for " + component.getName());
        return result.get();
    }

    private static ComponentEntity component(String name, String version) {
        ComponentEntity component = new ComponentEntity();
        component.setFormat("nuget");
        component.setNamespace(null);
        component.setName(name);
        component.setVersion(version);
        return component;
    }
}
