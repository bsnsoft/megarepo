package de.bsnsoft.megarepo.repository.firewall.identity;

import java.util.Locale;
import java.util.Map;

/**
 * Selects the {@link VersionScheme} for a package ecosystem.
 *
 * <p>The lookup key is the <strong>purl type string</strong> ({@code maven},
 * {@code npm}, {@code pypi}, {@code nuget}, {@code docker}, {@code generic}) and
 * deliberately not a purl object: version ordering has no business depending on
 * the purl model, and keeping the key a plain string lets both sides evolve
 * independently.
 *
 * <p>The key is trimmed and lower-cased before lookup, so the ecosystem names
 * OSV uses in its own feed ({@code Maven}, {@code PyPI}, {@code NuGet}) resolve
 * to the same schemes as the canonical purl types.
 *
 * <p>An unknown, blank or {@code null} type resolves to {@link #GENERIC} rather
 * than failing. A firewall that cannot name the ecosystem still has to reach a
 * decision, and a predictable fallback order beats an exception on the download
 * path.
 *
 * <p>All schemes are stateless singletons and safe to share.
 */
public final class VersionSchemes {

    /** Maven coordinates — backed by Maven's own {@code ComparableVersion}. */
    public static final VersionScheme MAVEN = new MavenVersionScheme();

    /** npm — Semantic Versioning 2.0.0 precedence. */
    public static final VersionScheme SEMVER = new SemverVersionScheme();

    /** PyPI — PEP 440. */
    public static final VersionScheme PEP440 = new Pep440VersionScheme();

    /** NuGet — SemVer 2.0.0 plus four-part versions and case-folded labels. */
    public static final VersionScheme NUGET = new NuGetVersionScheme();

    /** Raw, docker and anything unrecognised — documented fallback ordering. */
    public static final VersionScheme GENERIC = new GenericVersionScheme();

    private static final Map<String, VersionScheme> BY_PURL_TYPE = Map.of(
            "maven", MAVEN,
            "npm", SEMVER,
            "pypi", PEP440,
            "nuget", NUGET,
            "docker", GENERIC,
            "generic", GENERIC);

    private VersionSchemes() {}

    /**
     * Returns the scheme for a purl type.
     *
     * @param purlType e.g. {@code maven}; case and surrounding whitespace are
     *                 ignored
     * @return the matching scheme, or {@link #GENERIC} when the type is
     *         unknown, blank or {@code null} — never {@code null}
     */
    public static VersionScheme forPurlType(String purlType) {
        if (purlType == null || purlType.isBlank()) {
            return GENERIC;
        }
        return BY_PURL_TYPE.getOrDefault(purlType.trim().toLowerCase(Locale.ROOT), GENERIC);
    }

    /** {@code true} when {@code purlType} maps to a real ecosystem scheme. */
    public static boolean isKnownPurlType(String purlType) {
        return purlType != null
                && !purlType.isBlank()
                && BY_PURL_TYPE.containsKey(purlType.trim().toLowerCase(Locale.ROOT));
    }
}
