package de.bsnsoft.megarepo.repository.firewall.rule.corpus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The distance the typosquat threshold is expressed in. */
class BoundedEditDistanceTest {

    @Test
    @DisplayName("a swapped pair of letters is one edit, not two")
    void transpositionCountsOnce() {
        // The reason the rule can run at maxDistance 1 at all: with plain
        // Levenshtein this pair is 2 and every fat-fingered squat slips through.
        assertThat(BoundedEditDistance.between("lodash", "lodahs", 1)).isEqualTo(1);
        assertThat(BoundedEditDistance.between("requests", "reqeusts", 1)).isEqualTo(1);
    }

    @Test
    @DisplayName("insert, delete and substitute each count once")
    void singleEdits() {
        assertThat(BoundedEditDistance.between("express", "expres", 1)).isEqualTo(1);
        assertThat(BoundedEditDistance.between("express", "expresss", 1)).isEqualTo(1);
        assertThat(BoundedEditDistance.between("express", "expresz", 1)).isEqualTo(1);
    }

    @Test
    @DisplayName("identical strings are distance 0")
    void identical() {
        assertThat(BoundedEditDistance.between("lodash", "lodash", 0)).isZero();
    }

    @Test
    @DisplayName("the bound is honoured on both sides of it")
    void boundary() {
        assertThat(BoundedEditDistance.between("minimist", "minimalist", 1))
                .isEqualTo(BoundedEditDistance.EXCEEDS_BOUND);
        assertThat(BoundedEditDistance.between("minimist", "minimalist", 2)).isEqualTo(2);
        assertThat(BoundedEditDistance.between("minimist", "minimalist", 3)).isEqualTo(2);
    }

    @Test
    @DisplayName("a length difference greater than the bound is rejected before any work")
    void lengthPreCheck() {
        assertThat(BoundedEditDistance.between("a", "abcdefgh", 2))
                .isEqualTo(BoundedEditDistance.EXCEEDS_BOUND);
    }

    @Test
    @DisplayName("the early exit does not change the answer")
    void earlyExitIsSound() {
        // Long strings whose first rows are already far apart but which end up
        // within the bound — the case a naive "abort on one bad row" gets wrong.
        assertThat(BoundedEditDistance.between("abcdefghij", "bacdefghij", 1)).isEqualTo(1);
        assertThat(BoundedEditDistance.between("abcdefghij", "bacdefghji", 2)).isEqualTo(2);
    }

    @Test
    @DisplayName("empty strings and a negative bound do not blow up")
    void degenerate() {
        assertThat(BoundedEditDistance.between("", "", 0)).isZero();
        assertThat(BoundedEditDistance.between("", "ab", 2)).isEqualTo(2);
        assertThat(BoundedEditDistance.between("ab", "ab", -1)).isZero();
        assertThat(BoundedEditDistance.between("ab", "ac", -1))
                .isEqualTo(BoundedEditDistance.EXCEEDS_BOUND);
    }

    @Test
    @DisplayName("distance is symmetric")
    void symmetry() {
        assertThat(BoundedEditDistance.between("lodash", "lodahs", 2))
                .isEqualTo(BoundedEditDistance.between("lodahs", "lodash", 2));
        assertThat(BoundedEditDistance.between("express", "expres", 2))
                .isEqualTo(BoundedEditDistance.between("expres", "express", 2));
    }
}
