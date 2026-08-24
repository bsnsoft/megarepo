package de.bsnsoft.megarepo.repository.advisory.ghsa;

import java.util.Locale;
import java.util.Optional;

/**
 * Maps a GHSA {@code package} ({@code ecosystem} + {@code name}) onto the purl
 * coordinates MegaRepo stores in {@code advisory_affected}.
 *
 * <p>Only the four ecosystems MegaRepo actually hosts are kept — maven, npm, pypi and
 * nuget. Everything else (Go, RubyGems, Composer, Rust, Actions, Swift, Pub, Hex, …) is
 * dropped by the caller and counted: storing advisories for artifacts the firewall can
 * never see would grow the lookup table without ever producing a match.
 *
 * <p>The names produced here must equal, byte for byte, what the format modules'
 * {@code PurlMapper}s produce for a locally stored component — otherwise the advisory
 * exists but never matches. Each rule below therefore mirrors one mapper:
 *
 * <ul>
 *   <li><b>maven</b> — GHSA writes {@code groupId:artifactId} in a single string;
 *       {@code MavenPurlMapper} puts the (dotted, case-sensitive) groupId in the purl
 *       namespace and the artifactId in the name. Split at the first colon, keep case.
 *       A Maven name without a colon yields nothing, exactly as the mapper refuses a
 *       component without a groupId — a bare artifactId is the ambiguous identity the
 *       purl migration exists to eliminate.</li>
 *   <li><b>npm</b> — {@code @scope/name} splits into namespace {@code @scope} (the
 *       {@code @} belongs to the namespace, as in {@code NpmPurlMapper}) and the bare
 *       name. Case is preserved: legacy registry names like {@code JSONStream} are
 *       published by GHSA with their original casing.</li>
 *   <li><b>pypi</b> — normalised per PEP 503 (lowercase, runs of {@code [-_.]} collapsed
 *       to one {@code -}), the same rule {@code PypiPurlMapper} applies through
 *       {@code PythonNameNormalizer}, so {@code zope.interface} and {@code Zope_Interface}
 *       land on the single identity {@code zope-interface}. The normalizer itself lives in
 *       {@code megarepo-format-pypi}, which depends on this module — it cannot be imported
 *       here, so the (two-line) rule is restated.</li>
 *   <li><b>nuget</b> — lowercased, mirroring {@code NugetPurlMapper}/{@code NugetNames}.
 *       NuGet ids are case-insensitive and the V3 protocol lowercases them everywhere;
 *       GHSA publishes the author's casing ({@code Newtonsoft.Json}), so lowercasing here
 *       is what makes the two identities meet.</li>
 * </ul>
 */
final class GhsaPackages {

    private GhsaPackages() {}

    /**
     * Purl coordinates of one affected package.
     *
     * @param purlType one of {@code maven}, {@code npm}, {@code pypi}, {@code nuget}
     * @param namespace purl namespace, null for nuget and pypi and unscoped npm
     * @param name purl name, normalised the way the ecosystem's PurlMapper normalises it
     */
    record Coordinates(String purlType, String namespace, String name) {}

    /**
     * Maps an ecosystem/name pair, or empty when the ecosystem is outside MegaRepo's
     * four formats or the name is unusable for that ecosystem.
     *
     * <p>Matching is case-insensitive because the two GitHub APIs disagree: the REST
     * advisories endpoint publishes {@code "maven"}, the GraphQL schema {@code MAVEN}.
     */
    static Optional<Coordinates> map(String ecosystem, String rawName) {
        String eco = trimToNull(ecosystem);
        String name = trimToNull(rawName);
        if (eco == null || name == null) {
            return Optional.empty();
        }
        return switch (eco.toLowerCase(Locale.ROOT)) {
            case "maven" -> maven(name);
            case "npm" -> npm(name);
            case "pip", "pypi" -> Optional.of(new Coordinates("pypi", null, pep503(name)));
            case "nuget" -> Optional.of(new Coordinates("nuget", null, name.toLowerCase(Locale.ROOT)));
            default -> Optional.empty();
        };
    }

    /**
     * Whether the ecosystem is one of MegaRepo's four. Lets the caller tell "we do not
     * host Go" apart from "this is a Maven advisory whose name we could not use" — two
     * very different numbers in a sync summary.
     */
    static boolean supports(String ecosystem) {
        String eco = trimToNull(ecosystem);
        if (eco == null) {
            return false;
        }
        return switch (eco.toLowerCase(Locale.ROOT)) {
            case "maven", "npm", "pip", "pypi", "nuget" -> true;
            default -> false;
        };
    }

    private static Optional<Coordinates> maven(String name) {
        int colon = name.indexOf(':');
        if (colon <= 0 || colon == name.length() - 1) {
            return Optional.empty();
        }
        String groupId = trimToNull(name.substring(0, colon));
        String artifactId = trimToNull(name.substring(colon + 1));
        if (groupId == null || artifactId == null) {
            return Optional.empty();
        }
        return Optional.of(new Coordinates("maven", groupId, artifactId));
    }

    private static Optional<Coordinates> npm(String name) {
        if (name.startsWith("@")) {
            int slash = name.indexOf('/');
            if (slash <= 1 || slash == name.length() - 1) {
                // "@" alone or "@scope" without a package name — not addressable.
                return Optional.empty();
            }
            String scope = trimToNull(name.substring(0, slash));
            String bare = trimToNull(name.substring(slash + 1));
            if (scope == null || bare == null) {
                return Optional.empty();
            }
            return Optional.of(new Coordinates("npm", scope, bare));
        }
        return Optional.of(new Coordinates("npm", null, name));
    }

    /** PEP 503 normalisation, identical to {@code PythonNameNormalizer#normalize}. */
    private static String pep503(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[-_.]+", "-");
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String trimmed = s.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
