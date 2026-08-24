package de.bsnsoft.megarepo.repository.firewall.rule.corpus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The normalisation everything else compares through. */
class NameSkeletonTest {

    @ParameterizedTest(name = "{0} and {1} are the same skeleton")
    @CsvSource({
            // Separator variants — the classic squat that an edit distance alone
            // would rate the same as a genuine typo.
            "python-dateutil, pythondateutil",
            "python-dateutil, python_dateutil",
            "python-dateutil, python.dateutil",
            // Digits standing in for letters.
            "lodash, l0dash",
            "requests, r3quests",
            "express, exprе55",
            // Cyrillic and Greek homoglyphs.
            "lodash, lоdash",
            "chalk, сhalk",
            "moment, mоment",
            // Two-character look-alikes.
            "modernizr, rnodernizr",
            "webpack, vvebpack",
            // Case and npm scope marker.
            "React, react",
            "@babel, babel"
    })
    void foldsToTheSameSkeleton(String left, String right) {
        assertThat(NameSkeleton.of(left)).isEqualTo(NameSkeleton.of(right));
    }

    @ParameterizedTest(name = "{0} and {1} stay distinct")
    @CsvSource({
            "lodash, lodahs",
            "express, expres",
            "commons-lang, commons-lang3",
            "left-pad, right-pad"
    })
    void keepsDifferentNamesDifferent(String left, String right) {
        assertThat(NameSkeleton.of(left)).isNotEqualTo(NameSkeleton.of(right));
    }

    @Test
    @DisplayName("a blank or null name is an empty skeleton rather than an exception")
    void handlesNothing() {
        assertThat(NameSkeleton.of(null)).isEmpty();
        assertThat(NameSkeleton.of("   ")).isEmpty();
        assertThat(NameSkeleton.plain(null)).isEmpty();
    }

    @Test
    @DisplayName("segments split a name into its family and its members")
    void segments() {
        assertThat(NameSkeleton.segments("lodash.get")).containsExactly("lodash", "get");
        assertThat(NameSkeleton.segments("@babel/preset-env"))
                .containsExactly("babel", "preset", "env");
        assertThat(NameSkeleton.segments("lodash")).containsExactly("lodash");
        assertThat(NameSkeleton.segments(null)).isEqualTo(List.of());
    }

    @ParameterizedTest(name = "{0} vs {1} is a version sibling")
    @CsvSource({
            "commons-lang, commons-lang3",
            "vue2, vue3",
            "urllib, urllib3"
    })
    void recognisesVersionSiblings(String left, String right) {
        assertThat(NameSkeleton.differsOnlyInTrailingDigits(left, right)).isTrue();
    }

    @Test
    @DisplayName("a digit in the middle is not a version suffix, and equal names are not siblings")
    void versionSiblingLimits() {
        assertThat(NameSkeleton.differsOnlyInTrailingDigits("lodash", "lodahs")).isFalse();
        assertThat(NameSkeleton.differsOnlyInTrailingDigits("lodash", "lodash")).isFalse();
        assertThat(NameSkeleton.differsOnlyInTrailingDigits("123", "1234")).isFalse();
    }
}
