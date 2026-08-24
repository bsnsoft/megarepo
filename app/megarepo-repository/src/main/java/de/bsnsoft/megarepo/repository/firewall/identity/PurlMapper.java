package de.bsnsoft.megarepo.repository.firewall.identity;

import com.github.packageurl.PackageURL;
import de.bsnsoft.megarepo.database.entity.ComponentEntity;

import java.util.Optional;
import java.util.Set;

/**
 * Format-specific translation of a stored {@link ComponentEntity} into a
 * <a href="https://github.com/package-url/purl-spec">package-URL</a>.
 *
 * <p>Each format module contributes exactly one implementation as a Spring
 * bean; {@link PurlBuilder} collects them all and dispatches on
 * {@link ComponentEntity#getFormat()}. This keeps the per-format coordinate
 * knowledge (Maven's dotted groupId, npm's {@code @scope}, PEP 503 name
 * normalisation, …) inside the module that owns it, so
 * {@code megarepo-repository} does not have to depend on all six format
 * modules.
 *
 * <p>The collection mechanism mirrors the existing format SPI: the mapper is a
 * plain {@code @Component} inside the module's own
 * {@code @ComponentScan}ed package, exactly like the coordinate extractors and
 * request handlers behind {@code FormatPlugin}. Indexing by
 * {@link #format()} plus {@link #formatAliases()} mirrors
 * {@code FormatRegistry.register}, so a component row carrying a legacy format
 * key ({@code "maven"} instead of {@code "maven2"}) still resolves.
 *
 * <p><b>Why purl and not CPE.</b> The NVD lookup this replaces guesses a CPE
 * product name from the artifact name alone and never looks at the namespace,
 * so {@code com.acme:util} and {@code org.other:util} collapse onto the same
 * product. A purl carries the namespace as a first-class part of the identity
 * and therefore cannot collapse the two.
 *
 * <p>Implementations must be side-effect free and must not perform I/O — the
 * firewall evaluates on the request path.
 */
public interface PurlMapper {

    /**
     * Canonical format key this mapper handles, matching
     * {@code FormatPlugin#getFormat()} (e.g. {@code "maven2"}, {@code "npm"}).
     */
    String format();

    /**
     * Additional format keys that resolve to this mapper, matching
     * {@code FormatPlugin#getAliases()}. Needed because component rows written
     * by the proxy path store {@code RepositoryConfig#format()} verbatim, which
     * may be a historical alias.
     */
    default Set<String> formatAliases() {
        return Set.of();
    }

    /**
     * Builds the purl for the given component, or {@link Optional#empty()} if
     * this format cannot express the component as a purl.
     *
     * <p>Empty is a legitimate, expected answer — not an error. Raw files carry
     * no package coordinates at all, and a Docker component is a mutable tag
     * over a layer stack rather than a released package version. For those,
     * {@link PurlBuilder} falls back to content-hash identity.
     *
     * <p>Implementations must never throw; malformed or incomplete coordinates
     * are reported as {@link Optional#empty()}.
     */
    Optional<PackageURL> toPurl(ComponentEntity component);

    /**
     * Trims a coordinate value and maps blank to {@code null}, so that the
     * "missing" and "present but empty" cases (the Docker extractor stores
     * {@code ""} for an absent namespace, the Maven extractor stores
     * {@code ""} for an absent classifier) are treated alike.
     */
    static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
