package de.bsnsoft.megarepo.repository.firewall.identity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.List;

import static de.bsnsoft.megarepo.repository.firewall.identity.VersionOrderAssert.assertAscending;
import static de.bsnsoft.megarepo.repository.firewall.identity.VersionOrderAssert.assertEquivalent;
import static de.bsnsoft.megarepo.repository.firewall.identity.VersionOrderAssert.assertLess;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The generic scheme has no ecosystem to be correct against, so these tests pin
 * the <em>documented</em> behaviour instead. Their job is to make the fallback
 * a decision rather than an accident: if someone changes the tokenizer, the
 * consequences show up here as a broken contract rather than as a firewall that
 * quietly reorders raw artifacts.
 */
class GenericVersionSchemeTest {

    private final VersionScheme scheme = VersionSchemes.GENERIC;

    @Test
    void identifiesAsGeneric() {
        assertEquals("generic", scheme.id());
    }

    @Test
    void ordersATypicalRawArtifactChain() {
        assertAscending(scheme, "1.0", "1.0.1", "1.0.2", "1.1", "1.9", "1.10", "2.0");
    }

    @ParameterizedTest(name = "{0} < {1}")
    @CsvSource({
        // digit runs compare numerically, so no lexical 10-before-9 surprise
        "1.9, 1.10",
        "1.0.9, 1.0.10",
        "2, 10",
        // a longer token list wins once the shared prefix is equal
        "1.0, 1.0.0",
        "1.0, 1.0.1",
        // ... which includes suffixes: unlike semver, a suffix ranks ABOVE the
        // bare version, because nothing here identifies it as a pre-release
        "1.0, 1.0-rc1",
        "1.0, 1.0-final",
        // a digit run outranks a non-digit run in the same position
        "1.rc, 1.2",
        "latest, 1.0",
        "stable, 2024.1",
        // non-digit runs compare case-insensitively
        "1.0-alpha, 1.0-beta",
    })
    void ordersGenericVersions(String lower, String higher) {
        assertLess(scheme, lower, higher);
    }

    @ParameterizedTest(name = "{0} == {1}")
    @CsvSource({
        // separators carry no weight of their own
        "1.0, 1-0",
        "1.0.0, 1-0_0",
        "1.0+2, 1.0.2",
        // leading zeros in a digit run do not change its value
        "1.007, 1.7",
        // surrounding whitespace is trimmed
        "'  1.0  ', 1.0",
    })
    void treatsEquivalentSpellingsAsEqual(String a, String b) {
        assertEquivalent(scheme, a, b);
    }

    @Test
    void trailingZerosAreSignificantUnlikeMavenOrPep440() {
        // Documented consequence: the generic scheme has no basis for declaring
        // 1.0 and 1.0.0 the same artifact, so it does not.
        assertLess(scheme, "1.0", "1.0.0");
        assertEquals(0, VersionSchemes.MAVEN.compare("1.0", "1.0.0"));
        assertEquals(0, VersionSchemes.PEP440.compare("1.0", "1.0.0"));
    }

    @Test
    void nullsSortLowest() {
        assertEquals(0, scheme.compare(null, null));
        assertTrue(scheme.compare(null, "1.0") < 0);
        assertTrue(scheme.compare("1.0", null) > 0);
    }

    /**
     * Docker tags are arbitrary strings. The requirement is not that the result
     * is meaningful but that it is stable — sorting the same set twice must not
     * produce two different answers.
     */
    @Test
    void sortingDockerTagsIsStableAndTotal() {
        List<String> tags = new ArrayList<>(
                List.of("latest", "1.21-alpine", "1.21", "1.9", "1.10", "edge", "", "3.19.1"));

        List<String> first = new ArrayList<>(tags);
        first.sort(scheme.comparator());

        List<String> second = new ArrayList<>(tags);
        java.util.Collections.reverse(second);
        second.sort(scheme.comparator());

        assertEquals(first, second, "sort order must not depend on input order");
    }

    @Test
    void handlesEmptyAndSeparatorOnlyInputWithoutThrowing() {
        assertEquals(0, scheme.compare("", ""));
        assertEquals(0, scheme.compare("", "..."), "neither yields any token");
        assertTrue(scheme.compare("", "1") < 0);
    }

    /** Segment values far beyond {@code long} range must not wrap around. */
    @Test
    void hugeNumericSegmentsDoNotOverflow() {
        assertLess(scheme, "1.99999999999999999999999998", "1.99999999999999999999999999");
    }
}
