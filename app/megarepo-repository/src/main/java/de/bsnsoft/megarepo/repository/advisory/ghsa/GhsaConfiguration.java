package de.bsnsoft.megarepo.repository.advisory.ghsa;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link GhsaProperties} for binding.
 *
 * <p>Kept next to the source rather than in the application module's central
 * {@code MegaRepoConfig}: the GHSA source is self-contained, and a deployment that never
 * sets {@code megarepo.firewall.ghsa.*} still gets a fully defaulted (token-less,
 * therefore inert) configuration.
 */
@Configuration
@EnableConfigurationProperties(GhsaProperties.class)
public class GhsaConfiguration {
}
