package de.bsnsoft.megarepo.format.docker.firewall;

import com.github.packageurl.PackageURL;
import de.bsnsoft.megarepo.database.entity.ComponentEntity;
import de.bsnsoft.megarepo.repository.firewall.identity.PurlMapper;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Docker components have no purl identity — always {@link Optional#empty()},
 * so {@code PurlBuilder} falls back to content-hash identity.
 *
 * <p>A Docker component in MegaRepo is a manifest addressed by a tag or a
 * digest, and neither makes a usable package identity:
 *
 * <ul>
 *   <li>A tag is mutable. {@code nginx:1.25} is a different set of bytes today
 *       than it was last month, so {@code pkg:docker/library/nginx@1.25} would
 *       name a moving target — the opposite of what an identity is for.</li>
 *   <li>The vulnerabilities in an image are not properties of the image name at
 *       all. They live in the OS and language packages inside its layers, which
 *       only a layer scan can enumerate; no advisory feed publishes ranges
 *       against {@code pkg:docker}.</li>
 * </ul>
 *
 * <p>The manifest digest is a genuine, immutable identity, and that is exactly
 * what hash identity gives: MegaRepo stores it as the asset's SHA-256, so a
 * Docker component can still be matched against known-bad digests and tracked
 * through quarantine. It just cannot be looked up in an advisory source.
 *
 * <p>Registering this mapper rather than leaving Docker unmapped is deliberate:
 * it records that the empty result is a decision about the format, not a
 * missing bean.
 */
@Component
public class DockerPurlMapper implements PurlMapper {

    @Override
    public String format() {
        return "docker";
    }

    @Override
    public Optional<PackageURL> toPurl(ComponentEntity component) {
        return Optional.empty();
    }
}
