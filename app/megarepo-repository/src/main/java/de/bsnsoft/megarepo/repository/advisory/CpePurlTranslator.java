package de.bsnsoft.megarepo.repository.advisory;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The one place where CPE and purl meet.
 *
 * <h2>Why this class cannot be lossless</h2>
 *
 * NVD identifies software with a CPE — {@code cpe:2.3:a:apache:log4j:2.14.1:*:*:*:*:*:*:*}
 * — while the firewall, OSV and GitHub Advisories identify packages with a purl
 * — {@code pkg:maven/org.apache.logging.log4j/log4j-core@2.14.1}. The two name
 * different things and no total mapping exists between them:
 *
 * <ul>
 *   <li><b>A CPE has no ecosystem.</b> {@code apache:log4j} does not say
 *       "Maven"; the same vendor/product pair covers the Java library, its
 *       Debian package and a vendor appliance that bundles it. The optional
 *       {@code target_sw} field would narrow this, but MegaRepo's NVD mirror
 *       ({@code cve_affected_products}, V8) stores only vendor, product and the
 *       version bounds, so even that hint is not available here.</li>
 *   <li><b>A CPE vendor is not a purl namespace.</b> {@code apache} is an
 *       organisation; {@code org.apache.logging.log4j} is a Maven groupId.
 *       There is no rule that derives one from the other, and thousands of
 *       counter-examples exist ({@code pivotal_software} publishes
 *       {@code org.springframework}).</li>
 *   <li><b>CPE product names are normalised destructively.</b> The CPE
 *       dictionary writes {@code commons_text} for the artifact
 *       {@code commons-text}. Reversing that is ambiguous — an underscore may
 *       have been a dash, a dot, or a literal underscore.</li>
 *   <li><b>A CPE product is coarser than an artifact.</b> One product
 *       {@code log4j} covers {@code log4j-core} and {@code log4j-api}, which are
 *       separately released artifacts with different vulnerabilities.</li>
 * </ul>
 *
 * <h2>What this class does instead</h2>
 *
 * It refuses to invent the missing information. A CPE is stored under the
 * reserved purl type {@link #PURL_TYPE} — deliberately <em>not</em> a
 * purl-spec type — with the CPE vendor in {@code purl_namespace} and the CPE
 * product in {@code purl_name}. The row is then, by construction, never mistaken
 * for a purl-native advisory: {@link AdvisoryLookupService} matches it on the
 * product name alone and labels every resulting finding
 * {@link MatchConfidence#HEURISTIC}. The vendor is carried along for display and
 * for the source-comparison report, never for matching, because matching on a
 * guessed vendor would trade the reported false positives for silent false
 * negatives.
 *
 * <p>{@link #productCandidatesFor(String)} generates the small set of CPE
 * product spellings a purl name may appear under. It is deliberately narrower
 * than the candidate generation in the legacy {@code NvdCveLookupService}: that
 * one also folded {@code log4j-api} onto the base product {@code log4j}, which
 * is precisely the defect pinned by {@code CpeGuessingVsPurlIdentityTest}. Only
 * separator normalisation is applied here — no truncation, no prefix folding.
 *
 * <h2>Version bounds</h2>
 *
 * CPE match ranges and OSV ranges nearly line up. The one gap is
 * {@code versionStartExcluding}: OSV's {@code introduced} bound is inclusive and
 * has no exclusive counterpart. It is mapped to {@code introduced} anyway, which
 * over-approximates by exactly the boundary version — a firewall that
 * over-reports one version is preferable to one that under-reports, and the
 * exact expression stays readable in {@code advisory_affected.version_range}.
 */
public final class CpePurlTranslator {

    /**
     * Reserved {@code purl_type} for CPE-derived affected ranges.
     *
     * <p>Not a type from the purl specification, and that is the point: a row
     * carrying it is not a purl and must never be compared to one as if it
     * were. {@code VersionSchemes.forPurlType("cpe")} resolves to the generic
     * scheme, which is also correct — a CPE names no ecosystem, so no
     * ecosystem-specific ordering can be justified for it. At lookup time the
     * <em>queried</em> component's scheme is used instead, because that is the
     * ecosystem the compared version string actually belongs to.
     */
    public static final String PURL_TYPE = "cpe";

    private CpePurlTranslator() {}

    /**
     * One CPE match as MegaRepo's NVD mirror stores it.
     *
     * <p>Mirrors the columns of {@code cve_affected_products}. Kept as a plain
     * value type rather than the JPA entity so the translation can be tested
     * and reasoned about without a database.
     */
    public record CpeMatch(
            String vendor,
            String product,
            String versionExact,
            String versionStartIncluding,
            String versionStartExcluding,
            String versionEndIncluding,
            String versionEndExcluding) {

        public CpeMatch {
            vendor = normalize(vendor);
            product = normalize(product);
            versionExact = trimToNull(versionExact);
            versionStartIncluding = trimToNull(versionStartIncluding);
            versionStartExcluding = trimToNull(versionStartExcluding);
            versionEndIncluding = trimToNull(versionEndIncluding);
            versionEndExcluding = trimToNull(versionEndExcluding);
        }

        /** A product affected in every version — the wildcarded CPE case. */
        public static CpeMatch allVersions(String vendor, String product) {
            return new CpeMatch(vendor, product, null, null, null, null, null);
        }
    }

    /**
     * Translates one CPE match into the purl-shaped affected range the advisory
     * store holds.
     *
     * @return empty when the CPE carries no product name, which is the only
     *     part of a CPE this translation genuinely requires. A missing vendor is
     *     tolerated — it is not used for matching anyway.
     */
    public static Optional<NormalizedAffected> translate(CpeMatch match) {
        if (match == null || match.product() == null) {
            return Optional.empty();
        }

        String introduced;
        String fixed;
        String lastAffected;

        if (match.versionExact() != null) {
            // A CPE pinned to a single version. Both OSV bounds are inclusive,
            // so [v, v] expresses "exactly v" without inventing a successor.
            introduced = match.versionExact();
            lastAffected = match.versionExact();
            fixed = null;
        } else {
            // versionStartIncluding wins when a feed publishes both, because it
            // is the bound that maps without loss.
            introduced = match.versionStartIncluding() != null
                    ? match.versionStartIncluding()
                    : match.versionStartExcluding();
            fixed = match.versionEndExcluding();
            lastAffected = match.versionEndIncluding();
        }

        return Optional.of(new NormalizedAffected(
                PURL_TYPE,
                match.vendor(),
                match.product(),
                describe(match),
                introduced,
                fixed,
                lastAffected));
    }

    /**
     * Human-readable rendering of the original CPE match, stored verbatim in
     * {@code advisory_affected.version_range}.
     *
     * <p>The V8 mirror does not keep the raw {@code cpe:2.3:…} URI, so this is a
     * faithful rendering of what it does keep rather than a reconstruction of
     * the upstream string. It is the only place the exclusive lower bound
     * survives after {@link #translate(CpeMatch)} widened it, which is what
     * makes the over-approximation auditable.
     */
    public static String describe(CpeMatch match) {
        StringBuilder out = new StringBuilder("cpe ");
        out.append(match.vendor() == null ? "*" : match.vendor())
                .append(':')
                .append(match.product() == null ? "*" : match.product());

        if (match.versionExact() != null) {
            return out.append(" =").append(match.versionExact()).toString();
        }

        StringBuilder bounds = new StringBuilder();
        appendBound(bounds, ">=", match.versionStartIncluding());
        appendBound(bounds, ">", match.versionStartExcluding());
        appendBound(bounds, "<", match.versionEndExcluding());
        appendBound(bounds, "<=", match.versionEndIncluding());

        return out.append(' ').append(bounds.isEmpty() ? "*" : bounds).toString();
    }

    /**
     * CPE product spellings a purl name may be published under, most likely
     * first.
     *
     * <p>Only separator normalisation, because that is the only transformation
     * the CPE dictionary is documented to apply. Nothing here shortens the name:
     * folding {@code log4j-api} onto {@code log4j} would re-introduce the exact
     * over-matching this design removes.
     *
     * @param purlName the purl name of the component being looked up
     * @return lower-cased candidates, or an empty set for a blank name
     */
    public static Set<String> productCandidatesFor(String purlName) {
        String name = trimToNull(purlName);
        if (name == null) {
            return Set.of();
        }
        String lower = name.toLowerCase(Locale.ROOT);
        Set<String> candidates = new LinkedHashSet<>(4);
        candidates.add(lower);
        candidates.add(lower.replace('-', '_'));
        candidates.add(lower.replace('.', '_'));
        candidates.add(lower.replace('-', '_').replace('.', '_'));
        return candidates;
    }

    /** {@code true} when a stored affected row came from a CPE. */
    public static boolean isCpeDerived(String purlType) {
        return PURL_TYPE.equalsIgnoreCase(trimToNull(purlType));
    }

    /** The confidence a stored affected row of this purl type can support. */
    public static MatchConfidence confidenceFor(String purlType) {
        return isCpeDerived(purlType) ? MatchConfidence.HEURISTIC : MatchConfidence.EXACT;
    }

    private static void appendBound(StringBuilder out, String operator, String value) {
        if (value == null) {
            return;
        }
        if (!out.isEmpty()) {
            out.append(", ");
        }
        out.append(operator).append(value);
    }

    /** CPE vendor and product are case-insensitive and stored lower-cased. */
    private static String normalize(String value) {
        String trimmed = trimToNull(value);
        return trimmed == null ? null : trimmed.toLowerCase(Locale.ROOT);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
