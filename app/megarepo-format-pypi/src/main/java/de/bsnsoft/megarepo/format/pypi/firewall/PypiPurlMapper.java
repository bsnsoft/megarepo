package de.bsnsoft.megarepo.format.pypi.firewall;

import com.github.packageurl.MalformedPackageURLException;
import com.github.packageurl.PackageURL;
import de.bsnsoft.megarepo.database.entity.ComponentEntity;
import de.bsnsoft.megarepo.format.pypi.naming.PythonNameNormalizer;
import de.bsnsoft.megarepo.repository.firewall.identity.PurlMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * {@code pkg:pypi/<normalized-name>@<version>}. PyPI has no namespace.
 *
 * <p>The name is normalised per PEP 503 — lowercased, with runs of
 * {@code [-_.]} collapsed to a single {@code -} — using the same
 * {@link PythonNameNormalizer} the upload path uses, so that
 * {@code zope.interface}, {@code zope_interface} and {@code Zope-Interface} all
 * land on the single identity {@code pkg:pypi/zope-interface}.
 *
 * <p>Normalising here rather than trusting the stored name is deliberate on two
 * counts: {@code packageurl-java} itself only lowercases and maps {@code _} to
 * {@code -} for the pypi type — it leaves dots alone, so
 * {@code zope.interface} would survive as a second, non-matching identity — and
 * the proxy caching path writes component rows through a different code path
 * than the upload handler.
 */
@Component
public class PypiPurlMapper implements PurlMapper {

    private static final Logger log = LoggerFactory.getLogger(PypiPurlMapper.class);

    private final PythonNameNormalizer nameNormalizer;

    public PypiPurlMapper(PythonNameNormalizer nameNormalizer) {
        this.nameNormalizer = nameNormalizer;
    }

    @Override
    public String format() {
        return "pypi";
    }

    @Override
    public Optional<PackageURL> toPurl(ComponentEntity component) {
        if (component == null) {
            return Optional.empty();
        }
        String name = PurlMapper.trimToNull(component.getName());
        String version = PurlMapper.trimToNull(component.getVersion());
        if (name == null) {
            return Optional.empty();
        }
        String normalized = PurlMapper.trimToNull(nameNormalizer.normalize(name));
        if (normalized == null) {
            return Optional.empty();
        }

        try {
            return Optional.of(new PackageURL(
                    PackageURL.StandardTypes.PYPI, null, normalized, version, null, null));
        } catch (MalformedPackageURLException e) {
            log.debug("Not a valid PyPI purl: {}@{}", normalized, version, e);
            return Optional.empty();
        }
    }
}
