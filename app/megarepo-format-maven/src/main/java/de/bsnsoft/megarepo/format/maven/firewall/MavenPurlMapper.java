package de.bsnsoft.megarepo.format.maven.firewall;

import com.github.packageurl.MalformedPackageURLException;
import com.github.packageurl.PackageURL;
import de.bsnsoft.megarepo.database.entity.ComponentEntity;
import de.bsnsoft.megarepo.repository.firewall.identity.PurlMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * {@code pkg:maven/<groupId>/<artifactId>@<version>}.
 *
 * <p>Maven components store the groupId in {@link ComponentEntity#getNamespace()}
 * in dotted form ({@code com.acme}), which is exactly what the purl namespace
 * wants — no conversion needed. Maven coordinates are case-sensitive, so
 * nothing is lowercased.
 *
 * <p>This mapper is the direct answer to the customer's complaint: the CPE
 * lookup it replaces derives its product candidates from the artifactId alone,
 * so {@code com.acme:util} and {@code org.other:util} match the same CPE
 * product {@code util}. Here the groupId is part of the identity, so the two
 * produce different purls and cannot be confused.
 *
 * <p>A component without a groupId yields no purl: {@code pkg:maven} requires
 * both namespace and name, and a bare artifactId is precisely the ambiguous
 * identity this change exists to eliminate.
 */
@Component
public class MavenPurlMapper implements PurlMapper {

    private static final Logger log = LoggerFactory.getLogger(MavenPurlMapper.class);

    /** Attribute key the Maven coordinate extractor uses for the artifact classifier. */
    static final String ATTR_CLASSIFIER = "classifier";

    /** Attribute key the Maven coordinate extractor uses for the file extension. */
    static final String ATTR_EXTENSION = "extension";

    @Override
    public String format() {
        return "maven2";
    }

    /**
     * Mirrors {@code MavenFormatPlugin#getAliases()} — repository rows from
     * older configs and pre-fix seeds carry {@code "maven"}, and the proxy
     * caching path copies that value onto the component.
     */
    @Override
    public Set<String> formatAliases() {
        return Set.of("maven");
    }

    @Override
    public Optional<PackageURL> toPurl(ComponentEntity component) {
        if (component == null) {
            return Optional.empty();
        }
        String groupId = PurlMapper.trimToNull(component.getNamespace());
        String artifactId = PurlMapper.trimToNull(component.getName());
        String version = PurlMapper.trimToNull(component.getVersion());
        if (groupId == null || artifactId == null) {
            return Optional.empty();
        }

        // Qualifiers distinguish the sources jar from the main jar. MegaRepo does
        // not currently persist the Maven coordinate extractor's formatAttributes
        // onto the component, so in practice this map stays empty; advisory
        // matching must use ComponentIdentity.Purl#coordinates() regardless,
        // because OSV and GHSA publish qualifier-free Maven purls.
        TreeMap<String, String> qualifiers = new TreeMap<>();
        putIfPresent(qualifiers, "classifier", attribute(component, ATTR_CLASSIFIER));
        putIfPresent(qualifiers, "type", attribute(component, ATTR_EXTENSION));

        try {
            return Optional.of(new PackageURL(
                    PackageURL.StandardTypes.MAVEN,
                    groupId,
                    artifactId,
                    version,
                    qualifiers.isEmpty() ? null : qualifiers,
                    null));
        } catch (MalformedPackageURLException e) {
            log.debug("Not a valid Maven purl: {}:{}:{}", groupId, artifactId, version, e);
            return Optional.empty();
        }
    }

    private static void putIfPresent(Map<String, String> qualifiers, String key, String value) {
        if (value != null) {
            qualifiers.put(key, value);
        }
    }

    private static String attribute(ComponentEntity component, String key) {
        Map<String, Object> attributes = component.getAttributes();
        if (attributes == null) {
            return null;
        }
        Object value = attributes.get(key);
        return value instanceof String s ? PurlMapper.trimToNull(s) : null;
    }
}
