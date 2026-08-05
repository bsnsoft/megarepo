package de.bsnsoft.megarepo.repository.firewall;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link FirewallAuditProperties} for binding.
 *
 * <p>Kept next to the observation code rather than in the application module's
 * {@code MegaRepoConfig}, for the same reason {@code GhsaConfiguration} is: a
 * deployment that never sets {@code megarepo.firewall.audit.*} still gets a
 * fully defaulted configuration, and the firewall stays removable as a unit.
 */
@Configuration
@EnableConfigurationProperties(FirewallAuditProperties.class)
public class FirewallAuditConfiguration {
}
