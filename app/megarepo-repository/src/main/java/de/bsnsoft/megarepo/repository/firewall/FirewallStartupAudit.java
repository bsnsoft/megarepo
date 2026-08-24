package de.bsnsoft.megarepo.repository.firewall;

import de.bsnsoft.megarepo.core.firewall.FirewallFailMode;
import de.bsnsoft.megarepo.core.firewall.FirewallMode;
import de.bsnsoft.megarepo.core.firewall.FirewallRuleType;
import de.bsnsoft.megarepo.database.entity.FirewallRepositoryConfigEntity;
import de.bsnsoft.megarepo.database.repository.FirewallRepositoryConfigJpaRepository;
import de.bsnsoft.megarepo.repository.firewall.facts.ComponentFactsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Says out loud, once at startup, when the configuration adds up to something
 * nobody would choose on purpose.
 *
 * <h2>The combination this exists for</h2>
 *
 * <pre>
 *   megarepo.firewall.facts.enabled = false
 *   + a repository in QUARANTINE with fail_mode = FAIL_CLOSED
 *   + a policy rule of type MIN_AGE or LICENSE
 * </pre>
 *
 * Each part is reasonable. Together they quarantine <em>everything new</em>,
 * permanently: the facts resolver is what fills in publication dates and declared
 * licenses, those two rules report {@code INDETERMINATE} without them, and a
 * fail-closed repository holds an undecidable component under
 * {@code EVALUATION_INCOMPLETE}. The sweep then re-evaluates the entry, finds the
 * fact still missing — because nothing is resolving it — and holds it again. The
 * queue fills up and nothing ever leaves it.
 *
 * <p>That is the <b>correct</b> behaviour of each individual part, which is
 * exactly why it needs saying. Nothing here changes a setting: an operator who
 * means it (an air-gapped instance that deliberately resolves nothing and
 * deliberately holds everything new for manual review) gets what they asked for.
 * They just get told.
 *
 * <p>At startup rather than per request. The condition is a property of the
 * deployment, and a log line per denied download would bury the diagnosis in the
 * symptom.
 */
@Component
public class FirewallStartupAudit {

    private static final Logger log = LoggerFactory.getLogger(FirewallStartupAudit.class);

    /** The rule types that cannot decide anything without the facts store. */
    private static final List<FirewallRuleType> FACTS_DEPENDENT =
            List.of(FirewallRuleType.MIN_AGE, FirewallRuleType.LICENSE);

    private final ComponentFactsProperties factsProperties;
    private final FirewallRepositoryConfigJpaRepository repositoryConfigs;
    private final FirewallPolicyEvaluator policies;

    public FirewallStartupAudit(
            ComponentFactsProperties factsProperties,
            FirewallRepositoryConfigJpaRepository repositoryConfigs,
            FirewallPolicyEvaluator policies) {
        this.factsProperties = factsProperties;
        this.repositoryConfigs = repositoryConfigs;
        this.policies = policies;
    }

    /**
     * Never throws and never blocks startup. A diagnostic that can prevent the
     * application from coming up is worse than the misconfiguration it warns
     * about.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void warnAboutPermanentQuarantine() {
        try {
            if (factsProperties.enabled()) {
                return;
            }
            List<FirewallRuleType> configured = FACTS_DEPENDENT.stream()
                    .filter(policies::anyPolicyEnables)
                    .toList();
            if (configured.isEmpty()) {
                return;
            }
            List<String> failClosed = failClosedQuarantineRepositories();
            if (failClosed.isEmpty()) {
                return;
            }

            log.warn("""
                    Repository firewall: this configuration holds every new component, permanently.
                      megarepo.firewall.facts.enabled = false, so publication dates and declared
                      licenses are never resolved. The configured rule(s) {} therefore report
                      INDETERMINATE for every component, and the fail-closed repositor(y|ies) {}
                      quarantine what cannot be decided under EVALUATION_INCOMPLETE. The
                      re-evaluation sweep will find the same missing fact and hold them again.
                      Nothing will leave that queue on its own.
                      If that is intended, ignore this. Otherwise: switch the facts resolver on,
                      set those repositories to FAIL_OPEN, or disable the rule(s).""",
                    configured, failClosed);

        } catch (RuntimeException e) {
            log.debug("Could not check the firewall configuration for the permanent-quarantine "
                    + "combination", e);
        }
    }

    private List<String> failClosedQuarantineRepositories() {
        List<String> names = new ArrayList<>();
        for (FirewallRepositoryConfigEntity config : repositoryConfigs.findAll()) {
            if (config.getMode() == FirewallMode.QUARANTINE
                    && config.getFailMode() == FirewallFailMode.FAIL_CLOSED) {
                names.add(String.valueOf(config.getRepositoryId()));
            }
        }
        return names;
    }
}
