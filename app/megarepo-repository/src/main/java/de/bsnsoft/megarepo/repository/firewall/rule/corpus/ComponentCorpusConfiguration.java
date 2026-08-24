package de.bsnsoft.megarepo.repository.firewall.rule.corpus;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link ComponentCorpusProperties}.
 *
 * <p>The firewall's other {@code megarepo.firewall.*} records are registered
 * together in {@code FirewallAuditConfiguration}, which the Phase 2 contract
 * filled in "so that the work packages that implement them do not have to edit a
 * shared file". The corpus was not foreseen there — the wave plan gives the
 * typosquat rule its parameters in the policy's {@code config}, and the scan
 * that feeds it turned out to need deployment-side settings of its own. Adding
 * them from this package keeps the contract commit untouched, which is worth
 * more than having exactly one list; if the two are ever consolidated, this
 * class is the thing to delete.
 */
@Configuration
@EnableConfigurationProperties(ComponentCorpusProperties.class)
public class ComponentCorpusConfiguration {
}
