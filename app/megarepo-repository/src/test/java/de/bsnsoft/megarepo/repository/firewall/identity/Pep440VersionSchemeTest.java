package de.bsnsoft.megarepo.repository.firewall.identity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static de.bsnsoft.megarepo.repository.firewall.identity.VersionOrderAssert.assertAscending;
import static de.bsnsoft.megarepo.repository.firewall.identity.VersionOrderAssert.assertEquivalent;
import static de.bsnsoft.megarepo.repository.firewall.identity.VersionOrderAssert.assertLess;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Pep440VersionSchemeTest {

    private final VersionScheme scheme = VersionSchemes.PEP440;

    @Test
    void identifiesAsPep440() {
        assertEquals("pep440", scheme.id());
    }

    /**
     * The ordering example from PEP 440 itself, verbatim and in full. It is the
     * tightest available check because it exercises every sort-key component —
     * dev, pre, post, local and epoch boundaries — in one chain.
     */
    @Test
    void pep440ReferenceOrdering() {
        assertAscending(
                scheme,
                "1.0.dev456",
                "1.0a1",
                "1.0a2.dev456",
                "1.0a12.dev456",
                "1.0a12",
                "1.0b1.dev456",
                "1.0b2",
                "1.0b2.post345.dev456",
                "1.0b2.post345",
                "1.0rc1.dev456",
                "1.0rc1",
                "1.0",
                "1.0+abc.5",
                "1.0+abc.7",
                "1.0+5",
                "1.0.post456.dev34",
                "1.0.post456",
                "1.1.dev1");
    }

    @ParameterizedTest(name = "{0} < {1}")
    @CsvSource({
        // rc sorts below the release, post sorts above it — the pair a single
        // generic comparator cannot get right at the same time
        "1.0rc1, 1.0",
        "1.0, 1.0.post1",
        "1.0rc1, 1.0.post1",
        // dev below everything of the same release
        "1.0.dev1, 1.0a1",
        "1.0.dev1, 1.0",
        "1.0.post1.dev1, 1.0.post1",
        // pre-release letters
        "1.0a1, 1.0b1",
        "1.0b1, 1.0rc1",
        // suffix numbers are numbers
        "1.0a2, 1.0a10",
        "1.0.post2, 1.0.post10",
        "1.0.dev2, 1.0.dev10",
        // an epoch outranks the release segment entirely
        "2.0, 1!1.0",
        "999.999, 1!0.1",
        "1!1.0, 2!0.1",
        // plain release ordering
        "1.0, 1.0.1",
        "1.9, 1.10",
        // a local label ranks above the same version without one
        "1.0, 1.0+ubuntu1",
        "1.0+ubuntu1, 1.0+ubuntu2",
        // numeric local segments outrank alphanumeric ones
        "1.0+abc, 1.0+1",
    })
    void ordersPep440Versions(String lower, String higher) {
        assertLess(scheme, lower, higher);
    }

    @ParameterizedTest(name = "{0} == {1}")
    @CsvSource({
        // trailing zeros in the release segment are irrelevant
        "1.0, 1.0.0",
        "1, 1.0.0",
        // the separator before a suffix is optional and interchangeable
        "1.0a1, 1.0-a1",
        "1.0a1, 1.0_a1",
        "1.0a1, 1.0.a1",
        // spellings fold onto the canonical suffix
        "1.0alpha1, 1.0a1",
        "1.0beta2, 1.0b2",
        "1.0c1, 1.0rc1",
        "1.0pre1, 1.0rc1",
        "1.0preview1, 1.0rc1",
        "1.0rev2, 1.0.post2",
        "1.0-r2, 1.0.post2",
        // the implicit post form: a bare -N after the release means .postN
        "1.0-1, 1.0.post1",
        "1.0-25, 1.0.post25",
        // an omitted suffix number is zero
        "1.0.post, 1.0.post0",
        "1.0.dev, 1.0.dev0",
        "1.0a, 1.0a0",
        // an explicit zero epoch is the default
        "0!1.0, 1.0",
        // a leading v is decoration
        "v1.0, 1.0",
        // leading zeros in numeric segments
        "1.01, 1.1",
    })
    void treatsEquivalentSpellingsAsEqual(String a, String b) {
        assertEquivalent(scheme, a, b);
    }

    @Test
    void nullsSortLowest() {
        assertEquals(0, scheme.compare(null, null));
        assertTrue(scheme.compare(null, "1.0") < 0);
        assertTrue(scheme.compare("1.0", null) > 0);
    }

    /**
     * A PyPI advisory that names a release candidate as the fix must not treat
     * the candidate's own release as still vulnerable, and the rc itself has to
     * fall inside the range.
     */
    @Test
    void rangeRespectsPreAndPostSuffixes() {
        VersionRange range = VersionRange.between("0", "2.0.1");

        assertTrue(scheme.contains(range, "1.0"));
        assertTrue(scheme.contains(range, "2.0.1rc1"), "an rc precedes its release");
        assertTrue(scheme.contains(range, "2.0.1.dev1"));

        assertFalse(scheme.contains(range, "2.0.1"), "the fixed version itself");
        assertFalse(
                scheme.contains(range, "2.0.1.post1"),
                "a post release of the fix is newer than the fix, not older");
    }

    @Test
    void unparseableInputSortsBelowParseableAndDoesNotThrow() {
        assertTrue(scheme.compare("not-a-version", "1.0") < 0);
        assertTrue(scheme.compare("1.0", "not-a-version") > 0);
        // two unparseable strings still get a stable, non-zero order
        assertTrue(scheme.compare("alpha", "beta") < 0);
        assertEquals(0, scheme.compare("garbage", "garbage"));
    }
}
