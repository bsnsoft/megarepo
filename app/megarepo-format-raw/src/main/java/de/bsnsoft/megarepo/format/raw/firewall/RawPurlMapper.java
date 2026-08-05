package de.bsnsoft.megarepo.format.raw.firewall;

import com.github.packageurl.PackageURL;
import de.bsnsoft.megarepo.database.entity.ComponentEntity;
import de.bsnsoft.megarepo.repository.firewall.identity.PurlMapper;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Raw components have no purl identity — always {@link Optional#empty()}, so
 * {@code PurlBuilder} falls back to content-hash identity.
 *
 * <p>A raw repository stores arbitrary files. Its "coordinates" are a directory
 * path and a filename, and its version is the literal {@code "1"} that
 * {@code RawCoordinateExtractor} hardcodes for every file. There is no package,
 * no publisher and no version, so there is nothing an advisory source could be
 * asked about. Emitting {@code pkg:generic/...} would produce a purl that looks
 * resolvable and never resolves — worse than admitting the format has no
 * package identity.
 *
 * <p>The file's SHA-256 is a real identity and is what the firewall uses
 * instead: it supports known-bad-hash matching and quarantine tracking, just
 * not advisory lookup.
 *
 * <p>Registering this mapper rather than leaving raw unmapped is deliberate: it
 * records that the empty result is a decision about the format, not a missing
 * bean.
 */
@Component
public class RawPurlMapper implements PurlMapper {

    @Override
    public String format() {
        return "raw";
    }

    @Override
    public Optional<PackageURL> toPurl(ComponentEntity component) {
        return Optional.empty();
    }
}
