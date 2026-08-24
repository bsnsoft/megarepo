package de.bsnsoft.megarepo.repository.firewall.exemption;

import com.github.packageurl.MalformedPackageURLException;
import com.github.packageurl.PackageURL;
import de.bsnsoft.megarepo.core.firewall.FirewallComponentKeyKind;
import de.bsnsoft.megarepo.core.firewall.FirewallExemptionScope;
import de.bsnsoft.megarepo.database.entity.FirewallExemptionEntity;
import de.bsnsoft.megarepo.repository.firewall.identity.ComponentIdentity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Every key an exemption may name a component by, and the comparison that says
 * whether a stored exemption covers it.
 *
 * <h2>Two schemes, four key forms</h2>
 *
 * A component is named by a purl key ({@code ComponentIdentity.key()}) and, when
 * the instance still carries rows migrated from the V8 whitelist, by the legacy
 * coordinate {@code format:namespace:name:version}. Each of the two has a
 * version-bearing and a version-less form, and the exemption's
 * {@link FirewallExemptionScope} decides which of the two it is compared
 * against:
 *
 * <ul>
 *   <li>{@link FirewallExemptionScope#VERSION} — the stored key must equal a
 *       version-bearing form. One version, and only that one.</li>
 *   <li>{@link FirewallExemptionScope#COMPONENT} — the stored key must equal a
 *       version-less form. Every version, including ones published after the
 *       exemption was approved.</li>
 * </ul>
 *
 * <p>Scope and key kind are matched together rather than by string prefix. A
 * {@code VERSION} row can never match through the version-less form and a
 * {@code COMPONENT} row can never match through the full one, which is exactly
 * the ambiguity the V8 whitelist had: there, one column silently meant both.
 *
 * <h2>Reproducing the V8 matcher</h2>
 *
 * {@code NvdFirewallService.isComponentWhitelisted} accepted an entry when it
 * equalled {@code buildComponentKey(component)} — {@code format:namespace:name:version},
 * namespace written as the empty string when absent — or when it equalled that
 * string with everything from the last colon onwards removed. V18 read that
 * behaviour off the old code and stored the scope accordingly (three colons →
 * {@code VERSION}, two → {@code COMPONENT}). This class produces the other half:
 * the same two strings, built from the component being evaluated.
 *
 * <p>The version-less legacy form is deliberately computed by cutting at the
 * last colon of the full form rather than by concatenating
 * {@code format:namespace:name}. For a version containing a colon the two differ,
 * and the V8 matcher did the former — bug-compatible is the requirement here, not
 * tidy.
 *
 * <h2>What a purl can and cannot say about a legacy key</h2>
 *
 * The legacy key was built from the stored {@code ComponentEntity}; a
 * {@link ComponentIdentity} is what is left after the format module has mapped
 * that entity to a purl. Recovering the coordinate therefore means undoing that
 * mapping, which is exact for the four ecosystems that produce purls at all:
 *
 * <ul>
 *   <li><b>maven</b> — groupId and artifactId are stored and mapped verbatim.
 *       Both format keys are emitted ({@code maven2} and its legacy alias
 *       {@code maven}), because a component row written by the proxy path carries
 *       whichever key its repository config had.</li>
 *   <li><b>npm</b> — the purl namespace always carries the {@code @}; the stored
 *       one may or may not, depending on which writer created the row, so both
 *       spellings are emitted. They denote the same scope, so this widens
 *       nothing.</li>
 *   <li><b>pypi</b> — names are PEP 503 normalised on the way in
 *       ({@code PypiCoordinateExtractor}, {@code PypiUploadHandler}), so the
 *       stored name and the purl name are the same string.</li>
 *   <li><b>nuget</b> — ids are lower-cased on the way in
 *       ({@code NugetPushHandler}), matching the purl.</li>
 * </ul>
 *
 * <p>A component identified only by content hash has no legacy form: the V8 key
 * needed coordinates and this identity has none. Such a component keeps being
 * evaluated by the V8 firewall, which is still running from its own table, so
 * nothing an operator allowed stops being allowed — it is simply not expressible
 * as a Phase 2 exemption until they restate it against the purl or the digest.
 */
public final class ExemptionKeyBuilder {

    private static final Logger log = LoggerFactory.getLogger(ExemptionKeyBuilder.class);

    /** Longest key {@code firewall_exemption.component_key} accepts (V17). */
    public static final int MAX_KEY_LENGTH = 1000;

    private ExemptionKeyBuilder() {}

    /**
     * The keys a component can be matched by, split by scheme and by scope.
     *
     * <p>Order inside each set is insertion order, so the assembled {@code IN}
     * list is stable and query plans are comparable between runs.
     */
    public record CandidateKeys(
            Set<String> purlVersionKeys,
            Set<String> purlComponentKeys,
            Set<String> legacyVersionKeys,
            Set<String> legacyComponentKeys) {

        public CandidateKeys {
            purlVersionKeys = Set.copyOf(purlVersionKeys);
            purlComponentKeys = Set.copyOf(purlComponentKeys);
            legacyVersionKeys = Set.copyOf(legacyVersionKeys);
            legacyComponentKeys = Set.copyOf(legacyComponentKeys);
        }

        /** Nothing can be named — a component the firewall cannot key on at all. */
        public static CandidateKeys none() {
            return new CandidateKeys(Set.of(), Set.of(), Set.of(), Set.of());
        }

        /** Every key form, for the single {@code IN} the lookup issues. */
        public Set<String> all() {
            Set<String> all = new LinkedHashSet<>();
            all.addAll(purlVersionKeys);
            all.addAll(purlComponentKeys);
            all.addAll(legacyVersionKeys);
            all.addAll(legacyComponentKeys);
            return all;
        }

        public boolean isEmpty() {
            return purlVersionKeys.isEmpty()
                    && purlComponentKeys.isEmpty()
                    && legacyVersionKeys.isEmpty()
                    && legacyComponentKeys.isEmpty();
        }

        /**
         * Whether a stored exemption names this component.
         *
         * <p>The {@code IN} the query issues is a superset filter — it cannot
         * express "this key, but only if the row's scope says so". This is the
         * part that can, and it is why a row that came back from the database is
         * not yet a match.
         */
        public boolean covers(FirewallExemptionEntity exemption) {
            if (exemption == null || exemption.getComponentKey() == null) {
                return false;
            }
            boolean legacy = exemption.getKeyKind() == FirewallComponentKeyKind.LEGACY_COORDINATE;
            boolean ignoresVersion =
                    exemption.getScopeType() != null && exemption.getScopeType().ignoresVersion();
            Set<String> applicable = legacy
                    ? (ignoresVersion ? legacyComponentKeys : legacyVersionKeys)
                    : (ignoresVersion ? purlComponentKeys : purlVersionKeys);
            return applicable.contains(exemption.getComponentKey());
        }
    }

    /**
     * Builds the candidate keys for a component.
     *
     * @param identity the component; null yields {@link CandidateKeys#none()}
     * @param includeLegacy whether the V8 coordinate forms are worth producing.
     *     The caller decides, because it knows whether any
     *     {@code LEGACY_COORDINATE} row exists — on an installation that never
     *     ran the V8 firewall those two strings would lengthen every lookup's
     *     {@code IN} list for keys that match nothing
     */
    public static CandidateKeys candidates(ComponentIdentity identity, boolean includeLegacy) {
        if (identity == null) {
            return CandidateKeys.none();
        }
        String key = safeKey(identity);
        if (key == null) {
            return CandidateKeys.none();
        }

        Set<String> purlVersion = new LinkedHashSet<>();
        Set<String> purlComponent = new LinkedHashSet<>();
        purlVersion.add(key);
        purlComponent.add(versionlessIdentityKey(identity, key));

        Set<String> legacyVersion = new LinkedHashSet<>();
        Set<String> legacyComponent = new LinkedHashSet<>();
        if (includeLegacy) {
            for (String legacy : legacyCoordinates(identity)) {
                legacyVersion.add(legacy);
                String prefix = withoutLastSegment(legacy);
                if (prefix != null) {
                    legacyComponent.add(prefix);
                }
            }
        }
        return new CandidateKeys(purlVersion, purlComponent, legacyVersion, legacyComponent);
    }

    /**
     * The key to store for a request, given the scope the requester chose.
     *
     * <p>A {@code COMPONENT}-scoped exemption is stored under the version-less
     * key, because that is the form {@link CandidateKeys#covers} compares a
     * {@code COMPONENT} row against. Storing the version-bearing key with a
     * {@code COMPONENT} scope would produce a row that reads as "every version"
     * in the UI and matches nothing at all — the silent-no-op failure this whole
     * table exists to remove.
     */
    public static String storageKey(String componentKey, FirewallExemptionScope scope) {
        if (componentKey == null) {
            return null;
        }
        String trimmed = componentKey.trim();
        if (scope == null || !scope.ignoresVersion()) {
            return trimmed;
        }
        return versionlessKey(trimmed);
    }

    /**
     * Strips the version from a stored key, whatever scheme it is in.
     *
     * <p>Public because it is the same question the API asks when it normalises
     * a request and the UI asks when it renders a {@code COMPONENT} row.
     */
    public static String versionlessKey(String key) {
        if (key == null || key.isBlank()) {
            return key;
        }
        if (key.startsWith("pkg:")) {
            return versionlessPurl(key);
        }
        // ComponentIdentity.Unidentified: "unidentified:<format>/<ns>/<name>@<version>".
        if (key.startsWith("unidentified:")) {
            int at = key.lastIndexOf('@');
            return at > 0 ? key.substring(0, at) : key;
        }
        // A content digest has no version; "every version of this" is the digest
        // itself, and cutting at a colon would maim the algorithm prefix.
        return key;
    }

    // ── purl forms ──────────────────────────────────────────────────────

    private static String versionlessIdentityKey(ComponentIdentity identity, String key) {
        if (identity instanceof ComponentIdentity.Purl purl) {
            return versionlessPurl(purl.purl());
        }
        return versionlessKey(key);
    }

    private static String versionlessPurl(String canonical) {
        try {
            return versionlessPurl(new PackageURL(canonical));
        } catch (MalformedPackageURLException e) {
            log.debug("Not a parseable purl, keeping it verbatim: {}", canonical, e);
            return canonical;
        }
    }

    /**
     * {@code pkg:maven/com.acme/util} from
     * {@code pkg:maven/com.acme/util@1.0?classifier=sources}.
     *
     * <p>Qualifiers go with the version. They distinguish artifacts of one
     * release — the sources jar from the main jar — and an exemption that covers
     * every version of a component covers every artifact of every version of it.
     */
    private static String versionlessPurl(PackageURL purl) {
        try {
            return new PackageURL(purl.getType(), purl.getNamespace(), purl.getName(), null, null, null)
                    .canonicalize();
        } catch (MalformedPackageURLException e) {
            log.debug("Cannot build the version-less form of {}", purl, e);
            return purl.canonicalize();
        }
    }

    // ── V8 legacy coordinate forms ──────────────────────────────────────

    /**
     * The {@code format:namespace:name:version} strings this component could
     * have been whitelisted under, in the shape
     * {@code NvdFirewallService.buildComponentKey} produced.
     */
    private static List<String> legacyCoordinates(ComponentIdentity identity) {
        if (identity instanceof ComponentIdentity.Unidentified unidentified) {
            // Nothing to undo: these are the stored coordinates.
            return List.of(legacyKey(
                    unidentified.format(),
                    unidentified.namespace(),
                    unidentified.name(),
                    unidentified.version()));
        }
        if (!(identity instanceof ComponentIdentity.Purl purl)) {
            // Hash identity — see the class comment.
            return List.of();
        }
        PackageURL packageUrl = purl.purl();
        String type = packageUrl.getType() == null
                ? null
                : packageUrl.getType().toLowerCase(Locale.ROOT);
        if (type == null) {
            return List.of();
        }
        String name = packageUrl.getName();
        String version = packageUrl.getVersion();
        String namespace = packageUrl.getNamespace();

        List<String> keys = new ArrayList<>(3);
        switch (type) {
            case "maven" -> {
                // MavenPurlMapper.format() plus its alias, both of which occur as
                // ComponentEntity.format in the field.
                keys.add(legacyKey("maven2", namespace, name, version));
                keys.add(legacyKey("maven", namespace, name, version));
            }
            case "npm" -> {
                keys.add(legacyKey("npm", namespace, name, version));
                if (namespace != null && namespace.startsWith("@")) {
                    keys.add(legacyKey("npm", namespace.substring(1), name, version));
                }
            }
            default -> keys.add(legacyKey(type, namespace, name, version));
        }
        return keys;
    }

    /** {@code NvdFirewallService.buildComponentKey}, with a null namespace written as empty. */
    private static String legacyKey(String format, String namespace, String name, String version) {
        return orEmpty(format)
                + ":" + orEmpty(namespace)
                + ":" + orEmpty(name)
                + ":" + orEmpty(version);
    }

    /**
     * The V8 prefix rule: everything before the last colon. Returns null when
     * there is no colon past position 0, which is the case the old matcher
     * refused as well.
     */
    private static String withoutLastSegment(String legacyKey) {
        int lastColon = legacyKey.lastIndexOf(':');
        return lastColon > 0 ? legacyKey.substring(0, lastColon) : null;
    }

    private static String safeKey(ComponentIdentity identity) {
        try {
            String key = identity.key();
            if (key == null || key.isBlank()) {
                return null;
            }
            return key.length() > MAX_KEY_LENGTH ? key.substring(0, MAX_KEY_LENGTH) : key;
        } catch (RuntimeException e) {
            log.warn("Component identity produced no key — no exemption can name it", e);
            return null;
        }
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
