package de.bsnsoft.megarepo.format.nuget.naming;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NugetNamesTest {

    @Test
    void lowerId_lowercasesAndTrims() {
        assertEquals("newtonsoft.json", NugetNames.lowerId("Newtonsoft.Json"));
        assertEquals("my.package", NugetNames.lowerId("  My.Package  "));
    }

    @Test
    void normalizeVersion_stripsLeadingZeroes() {
        assertEquals("1.2.3", NugetNames.normalizeVersion("1.02.3"));
        assertEquals("1.0.0", NugetNames.normalizeVersion("01.00.00"));
    }

    @Test
    void normalizeVersion_padsToThreeSegments() {
        assertEquals("1.0.0", NugetNames.normalizeVersion("1.0"));
        assertEquals("2.0.0", NugetNames.normalizeVersion("2"));
    }

    @Test
    void normalizeVersion_dropsZeroRevision() {
        assertEquals("1.0.0", NugetNames.normalizeVersion("1.0.0.0"));
        assertEquals("1.0.0.1", NugetNames.normalizeVersion("1.0.0.1"));
    }

    @Test
    void normalizeVersion_stripsBuildMetadata() {
        assertEquals("1.0.0", NugetNames.normalizeVersion("1.0.0+build.42"));
        assertEquals("1.0.0-beta.1", NugetNames.normalizeVersion("1.0.0-beta.1+sha.abc"));
    }

    @Test
    void normalizeVersion_keepsPrereleaseSuffix() {
        assertEquals("1.0.0-Beta1", NugetNames.normalizeVersion("1.0.0-Beta1"));
        assertEquals("1.2.0-rc.2", NugetNames.normalizeVersion("1.02-rc.2"));
    }

    @Test
    void lowerVersion_normalizesAndLowercases() {
        assertEquals("1.0.0-beta1", NugetNames.lowerVersion("1.0.0.0-Beta1"));
    }

    @Test
    void versionOrder_sortsNumericallyWithPrereleaseBelowRelease() {
        List<String> versions = new ArrayList<>(List.of(
                "2.0.0", "1.0.0", "1.0.0-beta", "1.10.0", "1.2.0", "1.0.0-alpha"));
        versions.sort(NugetNames.versionOrder());
        assertEquals(List.of("1.0.0-alpha", "1.0.0-beta", "1.0.0", "1.2.0", "1.10.0", "2.0.0"), versions);
    }
}
