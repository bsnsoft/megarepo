package de.bsnsoft.megarepo.format.nuget.firewall;

import com.github.packageurl.MalformedPackageURLException;
import com.github.packageurl.PackageURL;
import de.bsnsoft.megarepo.database.entity.ComponentEntity;
import de.bsnsoft.megarepo.format.nuget.naming.NugetNames;
import de.bsnsoft.megarepo.repository.firewall.identity.PurlMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * {@code pkg:nuget/<lowercased-id>@<version>}. NuGet has no namespace.
 *
 * <p>NuGet package ids are case-insensitive: {@code Newtonsoft.Json} and
 * {@code newtonsoft.json} are the same package, and the V3 protocol lowercases
 * the id on every URL. Identity therefore lowercases too, via the same
 * {@link NugetNames#lowerId(String)} the push handler uses — otherwise a
 * proxy-cached and a hosted copy of the same package would carry two different
 * identities.
 *
 * <p>Consequence for advisory matching: GHSA publishes NuGet purls with the
 * author's original casing ({@code pkg:nuget/Newtonsoft.Json}), so advisory
 * lookup on {@code pkg:nuget} must compare case-insensitively. The version is
 * left exactly as stored — normalising it would be version semantics, which
 * identity has no business deciding.
 */
@Component
public class NugetPurlMapper implements PurlMapper {

    private static final Logger log = LoggerFactory.getLogger(NugetPurlMapper.class);

    @Override
    public String format() {
        return "nuget";
    }

    @Override
    public Optional<PackageURL> toPurl(ComponentEntity component) {
        if (component == null) {
            return Optional.empty();
        }
        String id = PurlMapper.trimToNull(component.getName());
        String version = PurlMapper.trimToNull(component.getVersion());
        if (id == null) {
            return Optional.empty();
        }
        String lowerId = NugetNames.lowerId(id);

        try {
            return Optional.of(new PackageURL(
                    PackageURL.StandardTypes.NUGET, null, lowerId, version, null, null));
        } catch (MalformedPackageURLException e) {
            log.debug("Not a valid NuGet purl: {}@{}", lowerId, version, e);
            return Optional.empty();
        }
    }
}
