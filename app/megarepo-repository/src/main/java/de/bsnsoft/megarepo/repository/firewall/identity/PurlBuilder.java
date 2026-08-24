package de.bsnsoft.megarepo.repository.firewall.identity;

import com.github.packageurl.PackageURL;
import de.bsnsoft.megarepo.database.entity.ComponentEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Turns a stored {@link ComponentEntity} into the identity the firewall
 * evaluates against.
 *
 * <p>Replaces the CPE guessing in {@code NvdCveLookupService}, which derives
 * candidate product names from the artifact name alone — the namespace is
 * accepted as a parameter and never read, so {@code com.acme:util} and
 * {@code org.other:util} are indistinguishable to it. A purl keeps the
 * namespace in the identity, so the two are distinct by construction.
 *
 * <p>Format knowledge lives in the format modules: each contributes a
 * {@link PurlMapper} bean, collected here by Spring. Mappers are indexed by
 * their canonical format key and by every alias, the same way
 * {@code FormatRegistry} indexes {@code FormatPlugin}s — a canonical key always
 * wins over an alias, so an alias can never shadow another format's mapper.
 *
 * <p>This class performs no I/O and never throws on bad input: a component that
 * cannot be identified yields {@link ComponentIdentity.Unidentified}, not an
 * exception. The firewall runs on the request path, and a malformed coordinate
 * must not turn into a 500.
 */
@Component
public class PurlBuilder {

    private static final Logger log = LoggerFactory.getLogger(PurlBuilder.class);

    private final Map<String, PurlMapper> mappersByFormat;

    @Autowired
    public PurlBuilder(ObjectProvider<PurlMapper> mappers) {
        this(mappers.orderedStream().toList());
    }

    /** Visible for tests and for use without a Spring context. */
    public PurlBuilder(List<PurlMapper> mappers) {
        Map<String, PurlMapper> index = new LinkedHashMap<>();
        for (PurlMapper mapper : mappers) {
            String canonical = normalizeKey(mapper.format());
            if (canonical == null) {
                log.warn("Ignoring PurlMapper {} — it declares a blank format key",
                        mapper.getClass().getName());
                continue;
            }
            PurlMapper previous = index.put(canonical, mapper);
            if (previous != null && previous != mapper) {
                log.warn("Two PurlMappers claim format '{}': {} replaces {}",
                        canonical, mapper.getClass().getName(), previous.getClass().getName());
            }
        }
        // Aliases are added afterwards and never overwrite a canonical key.
        for (PurlMapper mapper : mappers) {
            for (String alias : mapper.formatAliases()) {
                String key = normalizeKey(alias);
                if (key == null || key.equals(normalizeKey(mapper.format()))) {
                    continue;
                }
                index.putIfAbsent(key, mapper);
            }
        }
        this.mappersByFormat = Collections.unmodifiableMap(index);
        log.debug("purl identity available for formats: {}", this.mappersByFormat.keySet());
    }

    /**
     * Builds the purl for a component, or empty if its format has no mapper or
     * the component lacks the coordinates the format's purl type requires.
     */
    public Optional<PackageURL> toPurl(ComponentEntity component) {
        if (component == null) {
            return Optional.empty();
        }
        String key = normalizeKey(component.getFormat());
        if (key == null) {
            return Optional.empty();
        }
        PurlMapper mapper = mappersByFormat.get(key);
        if (mapper == null) {
            log.debug("No PurlMapper registered for format '{}' — falling back to hash identity",
                    component.getFormat());
            return Optional.empty();
        }
        try {
            return mapper.toPurl(component);
        } catch (RuntimeException e) {
            // A mapper is not allowed to throw, but the firewall sits on the
            // request path — degrade to hash identity instead of failing the request.
            log.warn("PurlMapper {} failed for {}:{}:{} — falling back to hash identity",
                    mapper.getClass().getName(), component.getFormat(),
                    component.getNamespace(), component.getName(), e);
            return Optional.empty();
        }
    }

    /**
     * Identifies a component without a known content digest. Formats that
     * cannot produce a purl yield {@link ComponentIdentity.Unidentified};
     * prefer {@link #identify(ComponentEntity, String)} whenever the caller has
     * the asset's SHA-256 at hand.
     */
    public ComponentIdentity identify(ComponentEntity component) {
        return identify(component, null);
    }

    /**
     * Identifies a component, falling back to the given SHA-256 digest when the
     * format carries no package coordinates.
     *
     * @param sha256 hex SHA-256 of the component's asset, or {@code null} if
     *               unknown. MegaRepo stores this on {@code AssetEntity}; it is
     *               passed in rather than looked up so that this class stays
     *               free of database access.
     */
    public ComponentIdentity identify(ComponentEntity component, String sha256) {
        Optional<PackageURL> purl = toPurl(component);
        if (purl.isPresent()) {
            return new ComponentIdentity.Purl(purl.get());
        }
        String digest = PurlMapper.trimToNull(sha256);
        if (digest != null) {
            return ComponentIdentity.Hash.sha256(digest);
        }
        return new ComponentIdentity.Unidentified(
                component == null ? null : component.getFormat(),
                component == null ? null : component.getNamespace(),
                component == null ? null : component.getName(),
                component == null ? null : component.getVersion());
    }

    /** Every format key that resolves to a mapper, aliases included. Diagnostics only. */
    public Set<String> supportedFormatKeys() {
        return mappersByFormat.keySet();
    }

    private static String normalizeKey(String format) {
        String trimmed = PurlMapper.trimToNull(format);
        return trimmed == null ? null : trimmed.toLowerCase(Locale.ROOT);
    }
}
