package de.bsnsoft.megarepo.format.npm.scope;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScopedPackageResolverTest {

    private ScopedPackageResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new ScopedPackageResolver();
    }

    @Test
    void isScoped_scopedPackage_returnsTrue() {
        assertTrue(resolver.isScoped("@angular/core"));
    }

    @Test
    void isScoped_unscopedPackage_returnsFalse() {
        assertFalse(resolver.isScoped("lodash"));
    }

    @Test
    void isScoped_nullPackage_returnsFalse() {
        assertFalse(resolver.isScoped(null));
    }

    @Test
    void isScoped_atWithoutSlash_returnsFalse() {
        assertFalse(resolver.isScoped("@scope"));
    }

    @Test
    void getScope_scopedPackage_returnsScope() {
        assertEquals("@angular", resolver.getScope("@angular/core"));
    }

    @Test
    void getScope_unscopedPackage_throws() {
        assertThrows(IllegalArgumentException.class, () -> resolver.getScope("lodash"));
    }

    @Test
    void getPackageName_scopedPackage_returnsName() {
        assertEquals("core", resolver.getPackageName("@angular/core"));
    }

    @Test
    void getPackageName_unscopedPackage_returnsFullName() {
        assertEquals("lodash", resolver.getPackageName("lodash"));
    }

    @Test
    void getFullName_withScope_returnsScopedName() {
        assertEquals("@angular/core", resolver.getFullName("@angular", "core"));
    }

    @Test
    void getFullName_nullScope_returnsPlainName() {
        assertEquals("lodash", resolver.getFullName(null, "lodash"));
    }

    @Test
    void getFullName_blankScope_returnsPlainName() {
        assertEquals("lodash", resolver.getFullName("", "lodash"));
    }
}
