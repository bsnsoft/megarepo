package de.bsnsoft.megarepo.repository.firewall;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.bsnsoft.megarepo.core.firewall.FirewallFailMode;
import de.bsnsoft.megarepo.core.firewall.FirewallMode;
import de.bsnsoft.megarepo.core.firewall.FirewallRuleType;
import de.bsnsoft.megarepo.database.entity.FirewallRepositoryConfigEntity;
import de.bsnsoft.megarepo.database.repository.FirewallRepositoryConfigJpaRepository;
import de.bsnsoft.megarepo.repository.firewall.facts.ComponentFactsProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The one-shot startup warning about a configuration that holds everything, for
 * ever.
 *
 * <p>Three individually reasonable settings — the facts resolver off, a
 * fail-closed QUARANTINE repository, a rule that needs a fact — together
 * quarantine every new component permanently, because the sweep re-evaluates an
 * entry whose missing fact nothing is resolving. The assertions below are about
 * the conjunction: any two of the three must stay silent, or the warning becomes
 * one an operator learns to ignore.
 *
 * <p>The log line is what this class produces, so the log line is what is
 * asserted. There is nothing else to observe.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FirewallStartupAuditTest {

    @Mock private FirewallRepositoryConfigJpaRepository repositoryConfigs;
    @Mock private FirewallPolicyEvaluator policies;

    private ch.qos.logback.classic.Logger logger;
    private ListAppender<ILoggingEvent> appender;
    private boolean factsResolution = true;

    @BeforeEach
    void captureTheLog() {
        logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(FirewallStartupAudit.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void releaseTheLog() {
        logger.detachAppender(appender);
        appender.stop();
    }

    @Test
    @DisplayName("all three together: the warning fires and names the rule and the repository")
    void theCombinationIsAnnounced() {
        UUID repositoryId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        givenFactsResolution(false);
        when(policies.anyPolicyEnables(FirewallRuleType.MIN_AGE)).thenReturn(true);
        when(repositoryConfigs.findAll()).thenReturn(List.of(
                config(repositoryId, FirewallMode.QUARANTINE, FirewallFailMode.FAIL_CLOSED)));

        audit().warnAboutPermanentQuarantine();

        assertThat(warnings()).hasSize(1);
        ILoggingEvent warning = warnings().get(0);
        assertThat(warning.getFormattedMessage())
                .as("the operator has to be told which rule and which repository, or it is not actionable")
                .contains("MIN_AGE")
                .contains(repositoryId.toString())
                .contains("megarepo.firewall.facts.enabled = false");
        assertThat(warning.getFormattedMessage())
                .as("nothing here changes a setting, so it has to say what the three ways out are")
                .contains("FAIL_OPEN");
    }

    @Test
    @DisplayName("with the facts resolver on, nothing is even looked at")
    void factsResolutionOnIsSilent() {
        givenFactsResolution(true);

        audit().warnAboutPermanentQuarantine();

        assertThat(warnings()).isEmpty();
        verifyNoInteractions(policies, repositoryConfigs);
    }

    @Test
    @DisplayName("no facts-dependent rule configured, no problem to warn about")
    void noFactsDependentRuleIsSilent() {
        givenFactsResolution(false);
        when(policies.anyPolicyEnables(FirewallRuleType.MIN_AGE)).thenReturn(false);
        when(policies.anyPolicyEnables(FirewallRuleType.LICENSE)).thenReturn(false);

        audit().warnAboutPermanentQuarantine();

        assertThat(warnings()).isEmpty();
        verifyNoInteractions(repositoryConfigs);
    }

    @Test
    @DisplayName("a fail-open repository releases what it cannot decide, so there is nothing to say")
    void failOpenIsSilent() {
        givenFactsResolution(false);
        when(policies.anyPolicyEnables(FirewallRuleType.LICENSE)).thenReturn(true);
        when(repositoryConfigs.findAll()).thenReturn(List.of(
                config(UUID.randomUUID(), FirewallMode.QUARANTINE, FirewallFailMode.FAIL_OPEN)));

        audit().warnAboutPermanentQuarantine();

        assertThat(warnings()).isEmpty();
    }

    @Test
    @DisplayName("an AUDIT repository never holds anything, whatever its fail mode says")
    void auditModeIsSilent() {
        givenFactsResolution(false);
        when(policies.anyPolicyEnables(FirewallRuleType.MIN_AGE)).thenReturn(true);
        when(repositoryConfigs.findAll()).thenReturn(List.of(
                config(UUID.randomUUID(), FirewallMode.AUDIT, FirewallFailMode.FAIL_CLOSED)));

        audit().warnAboutPermanentQuarantine();

        assertThat(warnings()).isEmpty();
    }

    @Test
    @DisplayName("a diagnostic that can stop the application coming up is worse than the fault it reports")
    void itNeverThrows() {
        givenFactsResolution(false);
        when(policies.anyPolicyEnables(FirewallRuleType.MIN_AGE)).thenReturn(true);
        when(repositoryConfigs.findAll())
                .thenThrow(new IllegalStateException("the config table is not migrated yet"));

        assertThatCode(() -> audit().warnAboutPermanentQuarantine()).doesNotThrowAnyException();
        assertThat(warnings())
                .as("a failed check is not the same statement as 'your configuration is wrong'")
                .isEmpty();
    }

    // ------------------------------------------------------------------

    /** The audit as configured by the {@code givenFactsResolution} of this test. */
    private FirewallStartupAudit audit() {
        ComponentFactsProperties facts = new ComponentFactsProperties(
                factsResolution, true, 2, 100, Duration.ofSeconds(10), 5, Duration.ofDays(30));
        return new FirewallStartupAudit(facts, repositoryConfigs, policies);
    }

    private void givenFactsResolution(boolean enabled) {
        this.factsResolution = enabled;
    }

    private List<ILoggingEvent> warnings() {
        return appender.list.stream().filter(event -> event.getLevel() == Level.WARN).toList();
    }

    private static FirewallRepositoryConfigEntity config(
            UUID repositoryId, FirewallMode mode, FirewallFailMode failMode) {
        FirewallRepositoryConfigEntity config = new FirewallRepositoryConfigEntity();
        config.setRepositoryId(repositoryId);
        config.setMode(mode);
        config.setFailMode(failMode);
        return config;
    }
}
