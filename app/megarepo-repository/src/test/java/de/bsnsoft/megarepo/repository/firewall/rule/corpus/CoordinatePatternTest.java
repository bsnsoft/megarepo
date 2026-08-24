package de.bsnsoft.megarepo.repository.firewall.rule.corpus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The glob operators write internal namespaces in. */
class CoordinatePatternTest {

    @Test
    @DisplayName("an exact pattern matches exactly, case-insensitively")
    void exact() {
        CoordinatePattern pattern = CoordinatePattern.of("com.acme");
        assertThat(pattern.matches("com.acme")).isTrue();
        assertThat(pattern.matches("COM.Acme")).isTrue();
        assertThat(pattern.matches("com.acme.billing")).isFalse();
        assertThat(pattern.matches("org.other")).isFalse();
    }

    @Test
    @DisplayName("a prefix pattern covers the prefix itself — otherwise com.acme would be a hole")
    void prefixIncludesItself() {
        CoordinatePattern pattern = CoordinatePattern.of("com.acme.*");
        assertThat(pattern.matches("com.acme.billing")).isTrue();
        assertThat(pattern.matches("com.acme")).isTrue();
        assertThat(pattern.matches("com.acmex")).isFalse();
        assertThat(CoordinatePattern.of("@acme/*").matches("@acme")).isTrue();
        assertThat(CoordinatePattern.of("@acme/*").matches("@acme/tools")).isTrue();
    }

    @Test
    @DisplayName("stars match any run, including in the middle and at the front")
    void wildcards() {
        assertThat(CoordinatePattern.of("*").matches("anything")).isTrue();
        assertThat(CoordinatePattern.of("acme-*").matches("acme-client")).isTrue();
        assertThat(CoordinatePattern.of("*-internal").matches("billing-internal")).isTrue();
        assertThat(CoordinatePattern.of("com.*.internal").matches("com.acme.internal")).isTrue();
        assertThat(CoordinatePattern.of("com.*.internal").matches("com.acme.public")).isFalse();
    }

    @Test
    @DisplayName("a dot is a dot, not a regex wildcard")
    void noRegex() {
        assertThat(CoordinatePattern.of("com.acme").matches("comxacme")).isFalse();
    }

    @Test
    @DisplayName("blank patterns are dropped rather than matching everything")
    void blanks() {
        assertThat(CoordinatePattern.of("   ")).isNull();
        assertThat(CoordinatePattern.of(null)).isNull();
        assertThat(CoordinatePattern.all(Arrays.asList("com.acme", "  ", null))).hasSize(1);
        assertThat(CoordinatePattern.all(null)).isEmpty();
    }

    @Test
    @DisplayName("firstMatch reports which pattern hit, for the evidence text")
    void firstMatch() {
        List<CoordinatePattern> patterns = CoordinatePattern.all(List.of("org.other", "com.acme.*"));
        CoordinatePattern hit = CoordinatePattern.firstMatch(patterns, "com.acme.billing", null);
        assertThat(hit).isNotNull();
        assertThat(hit.raw()).isEqualTo("com.acme.*");
        assertThat(CoordinatePattern.firstMatch(patterns, "io.example")).isNull();
        assertThat(CoordinatePattern.firstMatch(List.of(), "com.acme")).isNull();
    }
}
