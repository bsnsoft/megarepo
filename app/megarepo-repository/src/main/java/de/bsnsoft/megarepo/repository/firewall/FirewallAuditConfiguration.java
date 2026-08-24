package de.bsnsoft.megarepo.repository.firewall;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the firewall's configuration properties for binding.
 *
 * <p>Kept next to the firewall code rather than in the application module's
 * {@code MegaRepoConfig}, for the same reason {@code GhsaConfiguration} is: a
 * deployment that never sets {@code megarepo.firewall.*} still gets a fully
 * defaulted configuration, and the firewall stays removable as a unit.
 *
 * <p>{@link FirewallEnforcementProperties} is only the deployment-side
 * <em>fallback</em> for the enforcement switch; the authoritative value lives in
 * {@code firewall_enforcement_settings} and is resolved by
 * {@link FirewallEnforcementSettingsService}, so that flipping the switch never
 * needs a restart.
 */
@Configuration
@EnableConfigurationProperties({FirewallAuditProperties.class, FirewallEnforcementProperties.class})
public class FirewallAuditConfiguration {
}
