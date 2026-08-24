package de.bsnsoft.megarepo.repository.firewall;

import de.bsnsoft.megarepo.repository.firewall.exemption.ExemptionProperties;
import de.bsnsoft.megarepo.repository.firewall.facts.ComponentFactsProperties;
import de.bsnsoft.megarepo.repository.firewall.quarantine.QuarantineProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the firewall's {@code megarepo.firewall.*} configuration records.
 *
 * <p>All of them here rather than one configuration class per sub-package: the
 * set of properties a deployment can set is a single fact about the product, and
 * an operator looking for "what can I configure" should find one list. The
 * Phase 2 records are registered by the contract commit so that the work packages
 * that implement them do not have to edit a shared file.
 */
@Configuration
@EnableConfigurationProperties({
        FirewallAuditProperties.class,
        FirewallEnforcementProperties.class,
        FirewallBlockProperties.class,
        QuarantineProperties.class,
        ExemptionProperties.class,
        ComponentFactsProperties.class
})
public class FirewallAuditConfiguration {
}
