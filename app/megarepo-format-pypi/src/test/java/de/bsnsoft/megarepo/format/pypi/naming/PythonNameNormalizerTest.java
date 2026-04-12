package de.bsnsoft.megarepo.format.pypi.naming;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PythonNameNormalizerTest {

    private PythonNameNormalizer normalizer;

    @BeforeEach
    void setUp() {
        normalizer = new PythonNameNormalizer();
    }

    @Test
    void normalize_lowercasesName() {
        assertEquals("requests", normalizer.normalize("Requests"));
    }

    @Test
    void normalize_replacesUnderscoresWithHyphen() {
        assertEquals("my-package", normalizer.normalize("my_package"));
    }

    @Test
    void normalize_replacesDotsWithHyphen() {
        assertEquals("my-package", normalizer.normalize("my.package"));
    }

    @Test
    void normalize_replacesRunsOfSeparatorsWithSingleHyphen() {
        assertEquals("my-package-name", normalizer.normalize("My_Package.Name"));
    }

    @Test
    void normalize_replacesConsecutiveSeparators() {
        assertEquals("a-b", normalizer.normalize("a_.-b"));
    }

    @Test
    void normalize_alreadyNormalized() {
        assertEquals("flask", normalizer.normalize("flask"));
    }

    @Test
    void normalize_upperCaseWithMixedSeparators() {
        assertEquals("my-great-package", normalizer.normalize("My_Great.Package"));
    }

    @Test
    void normalize_nullReturnsNull() {
        assertNull(normalizer.normalize(null));
    }

    @Test
    void normalize_emptyReturnsEmpty() {
        assertEquals("", normalizer.normalize(""));
    }

    @Test
    void normalize_hyphenatedName() {
        assertEquals("some-lib", normalizer.normalize("some-lib"));
    }
}
