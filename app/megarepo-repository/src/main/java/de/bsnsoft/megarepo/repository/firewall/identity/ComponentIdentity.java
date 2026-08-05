package de.bsnsoft.megarepo.repository.firewall.identity;

import com.github.packageurl.PackageURL;

import java.util.Locale;
import java.util.Objects;

/**
 * What the firewall knows a component <em>is</em>.
 *
 * <p>Everything downstream — advisory lookup, policy evaluation, quarantine and
 * exemption records — keys on an identity rather than on raw coordinates. There
 * are exactly three cases, in descending order of usefulness:
 *
 * <ol>
 *   <li>{@link Purl} — the component has package coordinates and can be named
 *       the same way advisory feeds name it. This is the only identity that
 *       supports advisory matching.</li>
 *   <li>{@link Hash} — the format carries no package coordinates (raw files,
 *       Docker layers), but the stored content has a digest. The component is
 *       still exactly identifiable, just not <em>describable</em>: it can be
 *       matched against known-bad hashes and tracked through quarantine, but no
 *       advisory feed can be queried for it.</li>
 *   <li>{@link Unidentified} — neither coordinates nor a digest were available.
 *       Carries the raw coordinates for diagnostics only.</li>
 * </ol>
 *
 * <p>{@link #key()} is the stable string form used as a storage/lookup key.
 */
public sealed interface ComponentIdentity {

    /**
     * Stable string form. Distinct identities never share a key: purl keys start
     * with {@code pkg:}, hash keys with the digest algorithm, unidentified keys
     * with {@code unidentified:}.
     */
    String key();

    /**
     * Whether this identity can be used to query advisory sources. Only
     * {@link Purl} can.
     */
    boolean isResolvable();

    /**
     * purl-based identity — the preferred form.
     *
     * @param purl never {@code null}
     */
    record Purl(PackageURL purl) implements ComponentIdentity {

        public Purl {
            Objects.requireNonNull(purl, "purl must not be null");
        }

        /** Canonical purl including qualifiers, e.g. {@code pkg:maven/com.acme/util@1.0}. */
        @Override
        public String key() {
            return purl.canonicalize();
        }

        @Override
        public boolean isResolvable() {
            return true;
        }

        /**
         * The purl without qualifiers, e.g. {@code pkg:maven/com.acme/util@1.0}
         * for a {@code ?classifier=sources} artifact.
         *
         * <p>Advisory feeds (OSV, GHSA) publish qualifier-free purls, so
         * advisory matching must compare on this form while quarantine and
         * exemption records key on the full {@link #key()} — a {@code sources}
         * jar and the main jar are the same vulnerable package but two distinct
         * stored artifacts.
         */
        public String coordinates() {
            return purl.getCoordinates();
        }
    }

    /**
     * Content-hash identity for formats without package coordinates.
     *
     * @param algorithm digest algorithm, lowercased (e.g. {@code sha256})
     * @param value     hex digest, lowercased
     */
    record Hash(String algorithm, String value) implements ComponentIdentity {

        public Hash {
            algorithm = requireText(algorithm, "algorithm").toLowerCase(Locale.ROOT);
            value = requireText(value, "value").toLowerCase(Locale.ROOT);
        }

        /** e.g. {@code sha256:e3b0c44298fc1c14…}. */
        @Override
        public String key() {
            return algorithm + ":" + value;
        }

        @Override
        public boolean isResolvable() {
            return false;
        }

        /** Convenience factory for the digest MegaRepo stores on every asset. */
        public static Hash sha256(String hex) {
            return new Hash("sha256", hex);
        }
    }

    /**
     * Neither package coordinates nor a content digest were available.
     *
     * <p>This is the input to the {@code UNKNOWN_COMPONENT} policy rule: the
     * firewall cannot say anything about such a component, and the policy — not
     * this class — decides whether that is acceptable.
     *
     * <p>The key is a diagnostic label. It is deliberately <em>not</em> stable
     * across formats or comparable with anything outside MegaRepo.
     */
    record Unidentified(String format, String namespace, String name, String version)
            implements ComponentIdentity {

        @Override
        public String key() {
            return "unidentified:" + orEmpty(format)
                    + "/" + orEmpty(namespace)
                    + "/" + orEmpty(name)
                    + "@" + orEmpty(version);
        }

        @Override
        public boolean isResolvable() {
            return false;
        }

        private static String orEmpty(String value) {
            return value == null ? "" : value;
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
