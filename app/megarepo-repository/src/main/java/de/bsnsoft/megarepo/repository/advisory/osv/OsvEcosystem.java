package de.bsnsoft.megarepo.repository.advisory.osv;

import java.util.Locale;
import java.util.Optional;

/**
 * The OSV ecosystems MegaRepo mirrors, and how an OSV package name becomes purl
 * components.
 *
 * <p>OSV publishes for roughly thirty ecosystems, most of them Linux distributions
 * (Debian, Ubuntu, Alpine, SUSE, …) plus language ecosystems MegaRepo does not host
 * (Go, crates.io, Packagist, RubyGems, Hex, Pub, …). Only the four formats MegaRepo
 * actually serves are mirrored; everything else is counted and dropped, because an
 * advisory about a package that can never enter this repository is storage and lookup
 * cost with no possible finding attached to it.
 *
 * <p>The name split mirrors what each format's {@code PurlMapper} does to a stored
 * component, because a range only matches if both sides normalise identically:
 *
 * <ul>
 *   <li><b>Maven</b> — OSV writes {@code groupId:artifactId}; the purl keeps the dotted
 *       groupId as namespace. Case-sensitive, like {@code MavenPurlMapper}.</li>
 *   <li><b>npm</b> — {@code @scope/name} splits into namespace {@code @scope} (the
 *       {@code @} belongs to the namespace) and the bare name. Case preserved, like
 *       {@code NpmPurlMapper}.</li>
 *   <li><b>PyPI</b> — PEP 503: lower-cased, runs of {@code [-_.]} collapsed to one
 *       {@code -}. Same rule as {@code PythonNameNormalizer}, restated here because
 *       {@code megarepo-repository} cannot depend on the format modules (they depend on
 *       it).</li>
 *   <li><b>NuGet</b> — lower-cased ids, same rule as {@code NugetNames.lowerId}.</li>
 * </ul>
 *
 * <p>OSV qualifies some ecosystem strings with a suffix after a colon —
 * {@code Debian:11}, {@code Maven:https://maven.google.com}, {@code Alpine:v3.16} — to
 * name the distribution release or the artifact repository. The suffix narrows where the
 * package comes from, not which package manager it belongs to, so it is stripped before
 * matching.
 */
public enum OsvEcosystem {

    /** {@code Maven} → {@code pkg:maven}. */
    MAVEN("Maven", "maven"),

    /** {@code npm} → {@code pkg:npm}. */
    NPM("npm", "npm"),

    /** {@code PyPI} → {@code pkg:pypi}. */
    PYPI("PyPI", "pypi"),

    /** {@code NuGet} → {@code pkg:nuget}. */
    NUGET("NuGet", "nuget");

    /** A package name split into the purl namespace/name pair for its ecosystem. */
    public record PurlName(String namespace, String name) {}

    private final String osvName;
    private final String purlType;

    OsvEcosystem(String osvName, String purlType) {
        this.osvName = osvName;
        this.purlType = purlType;
    }

    /** The ecosystem string as OSV writes it, also the bulk export's path segment. */
    public String osvName() {
        return osvName;
    }

    /** The purl type this ecosystem maps onto. */
    public String purlType() {
        return purlType;
    }

    /**
     * Resolves an OSV {@code package.ecosystem} value, ignoring any {@code :suffix}
     * qualifier and case.
     *
     * @return empty for an ecosystem MegaRepo does not mirror — an expected answer, not
     *     an error
     */
    public static Optional<OsvEcosystem> fromOsvName(String ecosystem) {
        if (ecosystem == null) {
            return Optional.empty();
        }
        String base = ecosystem;
        int colon = base.indexOf(':');
        if (colon >= 0) {
            base = base.substring(0, colon);
        }
        base = base.trim();
        if (base.isEmpty()) {
            return Optional.empty();
        }
        for (OsvEcosystem candidate : values()) {
            if (candidate.osvName.equalsIgnoreCase(base)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /**
     * Splits an OSV package name into purl namespace and name.
     *
     * @return empty when the name is unusable for this ecosystem — a Maven coordinate
     *     without a colon, a blank name, an npm name with a path separator but no scope
     */
    public Optional<PurlName> splitPackageName(String packageName) {
        String name = packageName == null ? null : packageName.trim();
        if (name == null || name.isEmpty()) {
            return Optional.empty();
        }
        return switch (this) {
            case MAVEN -> splitMaven(name);
            case NPM -> splitNpm(name);
            case PYPI -> nonBlank(normalizePypi(name)).map(n -> new PurlName(null, n));
            case NUGET -> nonBlank(name.toLowerCase(Locale.ROOT)).map(n -> new PurlName(null, n));
        };
    }

    private static Optional<PurlName> splitMaven(String name) {
        int colon = name.indexOf(':');
        if (colon <= 0 || colon == name.length() - 1) {
            // A bare artifactId is exactly the ambiguous identity purl exists to remove:
            // com.acme:util and org.other:util would collapse onto the same row.
            return Optional.empty();
        }
        String groupId = name.substring(0, colon).trim();
        String artifactId = name.substring(colon + 1).trim();
        if (groupId.isEmpty() || artifactId.isEmpty() || artifactId.indexOf(':') >= 0) {
            return Optional.empty();
        }
        return Optional.of(new PurlName(groupId, artifactId));
    }

    private static Optional<PurlName> splitNpm(String name) {
        if (name.startsWith("@")) {
            int slash = name.indexOf('/');
            if (slash <= 1 || slash == name.length() - 1) {
                return Optional.empty();
            }
            String scope = name.substring(0, slash).trim();
            String bare = name.substring(slash + 1).trim();
            if (bare.isEmpty() || bare.indexOf('/') >= 0) {
                return Optional.empty();
            }
            return Optional.of(new PurlName(scope, bare));
        }
        if (name.indexOf('/') >= 0) {
            return Optional.empty();
        }
        return Optional.of(new PurlName(null, name));
    }

    /** PEP 503 name normalisation, identical to {@code PythonNameNormalizer}. */
    private static String normalizePypi(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[-_.]+", "-");
    }

    private static Optional<String> nonBlank(String value) {
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }
}
