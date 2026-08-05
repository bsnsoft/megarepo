package de.bsnsoft.megarepo.format.npm.firewall;

import com.github.packageurl.MalformedPackageURLException;
import com.github.packageurl.PackageURL;
import de.bsnsoft.megarepo.database.entity.ComponentEntity;
import de.bsnsoft.megarepo.repository.firewall.identity.PurlMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * {@code pkg:npm/<name>@<version>}, or {@code pkg:npm/@scope/<name>@<version>}
 * for scoped packages — canonicalised as {@code pkg:npm/%40scope/name@version},
 * since {@code @} is percent-encoded in a purl namespace.
 *
 * <p>npm components store a scoped package split into
 * {@link ComponentEntity#getNamespace()} = {@code "@scope"} (leading {@code @}
 * included) and {@link ComponentEntity#getName()} = the bare package name.
 * Unscoped packages have a {@code null} namespace. Both the publish path and
 * the proxy coordinate extractor agree on this, but the mapper still re-splits
 * a name that arrives as {@code @scope/name} so that a differently-written row
 * cannot silently produce {@code pkg:npm/%40scope%2Fname}.
 *
 * <p>Case is preserved. The npm registry has been case-sensitive for legacy
 * packages since it stopped accepting uppercase names, and OSV/GHSA publish
 * those names with their original casing ({@code JSONStream}) — lowercasing
 * here would break the very advisory matching this identity exists for.
 */
@Component
public class NpmPurlMapper implements PurlMapper {

    private static final Logger log = LoggerFactory.getLogger(NpmPurlMapper.class);

    @Override
    public String format() {
        return "npm";
    }

    @Override
    public Optional<PackageURL> toPurl(ComponentEntity component) {
        if (component == null) {
            return Optional.empty();
        }
        String namespace = PurlMapper.trimToNull(component.getNamespace());
        String name = PurlMapper.trimToNull(component.getName());
        String version = PurlMapper.trimToNull(component.getVersion());
        if (name == null) {
            return Optional.empty();
        }

        // Defensive: a row whose name still carries the scope.
        if (namespace == null && name.startsWith("@") && name.indexOf('/') > 0) {
            int slash = name.indexOf('/');
            namespace = name.substring(0, slash);
            name = PurlMapper.trimToNull(name.substring(slash + 1));
            if (name == null) {
                return Optional.empty();
            }
        }

        // A scope is always written with its '@' in a purl namespace.
        if (namespace != null && !namespace.startsWith("@")) {
            namespace = "@" + namespace;
        }

        try {
            return Optional.of(new PackageURL(
                    PackageURL.StandardTypes.NPM, namespace, name, version, null, null));
        } catch (MalformedPackageURLException e) {
            log.debug("Not a valid npm purl: {}/{}@{}", namespace, name, version, e);
            return Optional.empty();
        }
    }
}
