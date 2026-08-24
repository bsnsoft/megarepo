package de.bsnsoft.megarepo.repository.firewall.rule.corpus;

import com.github.packageurl.MalformedPackageURLException;
import com.github.packageurl.PackageURL;

import java.time.Instant;

/**
 * Builds synthetic corpora for the rule tests.
 *
 * <p>Deliberately goes through {@link ComponentNameCorpus.Builder} and real
 * {@link PackageURL}s rather than constructing entries directly: the indexes and
 * the skeletons are part of what the tests are checking, and a fixture that
 * hand-built {@link CorpusEntry} objects would test the assertions against
 * themselves.
 */
public final class CorpusFixtures {

    private CorpusFixtures() {
    }

    public static Builder corpus() {
        return new Builder();
    }

    /** A package-URL for a test coordinate. */
    public static PackageURL purl(String type, String namespace, String name, String version) {
        try {
            return new PackageURL(type, namespace, name, version, null, null);
        } catch (MalformedPackageURLException e) {
            throw new IllegalArgumentException(
                    "test coordinate is not a valid purl: %s/%s/%s".formatted(type, namespace, name), e);
        }
    }

    public static final class Builder {

        private final ComponentNameCorpus.Builder delegate = ComponentNameCorpus.builder();

        /** A package this instance proxied from upstream, in {@code versions} versions. */
        public Builder proxied(String type, String namespace, String name, int versions) {
            return add(type, namespace, name, versions, false, "central-proxy");
        }

        /** A package this instance publishes itself. */
        public Builder hosted(String type, String namespace, String name, int versions) {
            return add(type, namespace, name, versions, true, "internal-hosted");
        }

        public Builder add(
                String type, String namespace, String name, int versions,
                boolean hosted, String repository) {
            for (int i = 0; i < Math.max(1, versions); i++) {
                delegate.add(purl(type, namespace, name, "1.0." + i), hosted, repository);
            }
            return this;
        }

        public ComponentNameCorpus build() {
            return delegate.build(Instant.now());
        }
    }
}
