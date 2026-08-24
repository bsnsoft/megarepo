package de.bsnsoft.megarepo.repository.firewall.rule.corpus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The index the two rules read on the download path. */
class ComponentNameCorpusTest {

    @Test
    @DisplayName("every version of one name collapses into one entry that counts them")
    void versionsCollapse() {
        ComponentNameCorpus corpus = CorpusFixtures.corpus()
                .proxied("npm", null, "lodash", 4)
                .build();

        assertThat(corpus.size()).isEqualTo(1);
        assertThat(corpus.entries().get(0).versions()).isEqualTo(4);
        assertThat(corpus.scannedComponents()).isEqualTo(4);
        assertThat(corpus.neverLoaded()).isFalse();
    }

    @Test
    @DisplayName("a name that has never been loaded is distinguishable from one that is not there")
    void neverLoadedIsItsOwnState() {
        assertThat(ComponentNameCorpus.notLoadedYet().neverLoaded()).isTrue();
        assertThat(ComponentNameCorpus.notLoadedYet().isEmpty()).isTrue();
        assertThat(CorpusFixtures.corpus().build().neverLoaded()).isFalse();
        assertThat(CorpusFixtures.corpus().build().isEmpty()).isTrue();
    }

    @Test
    @DisplayName("candidates are drawn from the length window and from one ecosystem only")
    void lengthWindowAndEcosystem() {
        ComponentNameCorpus corpus = CorpusFixtures.corpus()
                .proxied("npm", null, "lodash", 1)      // skeleton length 6
                .proxied("npm", null, "lodashy", 1)     // 7
                .proxied("npm", null, "ms", 1)          // 2
                .proxied("maven", "com.acme", "lodash", 1)
                .build();

        assertThat(names(corpus, "npm", 6, 1))
                .containsExactlyInAnyOrder("lodash", "lodashy");
        assertThat(names(corpus, "npm", 6, 0)).containsExactly("lodash");
        assertThat(names(corpus, "maven", 6, 0)).containsExactly("lodash");
    }

    @Test
    @DisplayName("the skeleton index finds separator and homoglyph variants of one name")
    void skeletonIndex() {
        ComponentNameCorpus corpus = CorpusFixtures.corpus()
                .proxied("pypi", null, "python-dateutil", 2)
                .build();

        assertThat(corpus.byNameSkeleton("pypi", NameSkeleton.of("pythondateutil")))
                .singleElement()
                .satisfies(entry -> assertThat(entry.name()).isEqualTo("python-dateutil"));
        assertThat(corpus.byNameSkeleton("pypi", "nothing")).isEmpty();
    }

    @Test
    @DisplayName("namespace and family sizes count distinct names, not versions")
    void counts() {
        ComponentNameCorpus corpus = CorpusFixtures.corpus()
                .proxied("npm", "@babel", "core", 5)
                .proxied("npm", "@babel", "preset-env", 1)
                .proxied("npm", "@babel", "runtime", 1)
                .proxied("npm", null, "lodash.get", 1)
                .proxied("npm", null, "lodash.set", 1)
                .build();

        assertThat(corpus.namesInNamespace("npm", "@babel")).isEqualTo(3);
        assertThat(corpus.namesInNamespace("npm", "@BABEL")).isEqualTo(3);
        // The exactness that keeps a look-alike from inheriting the real
        // namespace's standing: @babe1 folds onto @babel but is not it.
        assertThat(corpus.namesInNamespace("npm", "@babe1")).isZero();
        assertThat(corpus.namesInNamespace("npm", null)).isEqualTo(2);
        assertThat(corpus.namesInFamily("npm", "", "lodash")).isEqualTo(2);
        assertThat(corpus.namesInFamily("npm", "", "nothing")).isZero();
    }

    @Test
    @DisplayName("hosted lookups are exact, and a proxied package never counts as hosted")
    void hostedLookup() {
        ComponentNameCorpus corpus = CorpusFixtures.corpus()
                .hosted("maven", "com.acme", "billing", 2)
                .proxied("maven", "org.other", "util", 1)
                .hosted("pypi", null, "acme-internal", 1)
                .build();

        assertThat(corpus.hostedInNamespace("maven", "com.acme")).isNotNull();
        assertThat(corpus.hostedInNamespace("maven", "COM.ACME")).isNotNull();
        // A look-alike namespace is not the internal one — that is the other
        // rule's question, and answering it here would let a squat pass as ours.
        assertThat(corpus.hostedInNamespace("maven", "com.acrne")).isNull();
        assertThat(corpus.hostedInNamespace("maven", "org.other")).isNull();
        assertThat(corpus.hostedCoordinate("pypi", null, "acme-internal")).isNotNull();
        assertThat(corpus.hostedCoordinate("pypi", null, "acme-external")).isNull();
        assertThat(corpus.hostedInNamespace("maven", "com.acme").exampleRepository())
                .isEqualTo("internal-hosted");
    }

    @Test
    @DisplayName("a name held in both a hosted and a proxy repository counts as hosted")
    void hostedWins() {
        ComponentNameCorpus corpus = CorpusFixtures.corpus()
                .proxied("maven", "com.acme", "billing", 1)
                .hosted("maven", "com.acme", "billing", 1)
                .build();

        assertThat(corpus.size()).isEqualTo(1);
        assertThat(corpus.entries().get(0).hosted()).isTrue();
        assertThat(corpus.hostedInNamespace("maven", "com.acme")).isNotNull();
    }

    @Test
    @DisplayName("an exact coordinate lookup says how established the requested package is")
    void coordinateLookup() {
        ComponentNameCorpus corpus = CorpusFixtures.corpus()
                .proxied("npm", null, "lodash", 3)
                .proxied("npm", "@babel", "core", 2)
                .build();

        assertThat(corpus.versionsOf("npm", null, "lodash")).isEqualTo(3);
        assertThat(corpus.versionsOf("npm", "@babel", "core")).isEqualTo(2);
        // Exact, not folded: the look-alike is a different package.
        assertThat(corpus.versionsOf("npm", null, "l0dash")).isZero();
        assertThat(corpus.versionsOf("npm", null, "unknown")).isZero();
        assertThat(corpus.entryFor("npm", null, "unknown")).isNull();
    }

    @Test
    @DisplayName("a coordinate reads the way a developer writes it")
    void coordinateFormatting() {
        ComponentNameCorpus corpus = CorpusFixtures.corpus()
                .proxied("maven", "com.acme", "util", 1)
                .proxied("npm", "@babel", "core", 1)
                .proxied("pypi", null, "requests", 1)
                .build();

        assertThat(corpus.entries().stream().map(CorpusEntry::coordinate))
                .containsExactlyInAnyOrder("com.acme:util", "@babel/core", "requests");
    }

    private static List<String> names(
            ComponentNameCorpus corpus, String type, int length, int maxDistance) {
        List<String> found = new ArrayList<>();
        corpus.forEachCandidateByLength(type, length, maxDistance, entry -> found.add(entry.name()));
        return found;
    }
}
