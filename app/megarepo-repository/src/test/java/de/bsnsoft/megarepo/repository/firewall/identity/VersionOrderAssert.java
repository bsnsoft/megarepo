package de.bsnsoft.megarepo.repository.firewall.identity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Assertions shared by the version scheme tests.
 *
 * <p>A comparator is only useful if it is a real order, so these helpers check
 * more than the single pair under test: antisymmetry on every pair, and — for
 * chains — that sorting a shuffled copy reproduces the chain, which catches
 * comparators that answer individual pairs correctly but are not transitive.
 */
final class VersionOrderAssert {

    private VersionOrderAssert() {}

    /** Asserts {@code lower < higher} and, symmetrically, {@code higher > lower}. */
    static void assertLess(VersionScheme scheme, String lower, String higher) {
        assertTrue(
                scheme.compare(lower, higher) < 0,
                () -> scheme.id() + ": expected " + lower + " < " + higher
                        + " but compare returned " + scheme.compare(lower, higher));
        assertTrue(
                scheme.compare(higher, lower) > 0,
                () -> scheme.id() + ": expected " + higher + " > " + lower
                        + " but compare returned " + scheme.compare(higher, lower));
    }

    /** Asserts the two spellings denote the same version in both directions. */
    static void assertEquivalent(VersionScheme scheme, String a, String b) {
        assertEquals(0, scheme.compare(a, b), () -> scheme.id() + ": expected " + a + " == " + b);
        assertEquals(0, scheme.compare(b, a), () -> scheme.id() + ": expected " + b + " == " + a);
    }

    /**
     * Asserts the versions form a strictly ascending chain, that every pair in
     * the chain is ordered consistently (not just neighbours), and that sorting
     * a shuffled copy reproduces the chain exactly.
     */
    static void assertAscending(VersionScheme scheme, String... versions) {
        for (int i = 0; i < versions.length; i++) {
            assertEquals(
                    0,
                    scheme.compare(versions[i], versions[i]),
                    () -> scheme.id() + ": compare must be reflexive");
            for (int j = i + 1; j < versions.length; j++) {
                assertLess(scheme, versions[i], versions[j]);
            }
        }

        List<String> expected = Arrays.asList(versions);
        List<String> shuffled = new ArrayList<>(expected);
        Collections.shuffle(shuffled, new Random(20260805L));
        shuffled.sort(scheme.comparator());
        assertEquals(
                expected,
                shuffled,
                () -> scheme.id() + ": sorting a shuffled copy must reproduce the chain");
    }
}
