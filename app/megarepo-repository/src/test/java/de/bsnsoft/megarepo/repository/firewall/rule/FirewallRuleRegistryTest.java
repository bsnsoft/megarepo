package de.bsnsoft.megarepo.repository.firewall.rule;

import com.github.packageurl.MalformedPackageURLException;
import com.github.packageurl.PackageURL;
import de.bsnsoft.megarepo.core.firewall.FirewallAction;
import de.bsnsoft.megarepo.core.firewall.FirewallFailMode;
import de.bsnsoft.megarepo.core.firewall.FirewallMode;
import de.bsnsoft.megarepo.core.firewall.FirewallQuarantineReason;
import de.bsnsoft.megarepo.core.firewall.FirewallRuleType;
import de.bsnsoft.megarepo.core.repository.RepositoryType;
import de.bsnsoft.megarepo.repository.firewall.FirewallRepositorySettings;
import de.bsnsoft.megarepo.repository.firewall.FirewallRuleViolation;
import de.bsnsoft.megarepo.repository.firewall.facts.ComponentFactsService;
import de.bsnsoft.megarepo.repository.firewall.identity.ComponentIdentity;
import de.bsnsoft.megarepo.repository.firewall.rule.corpus.ComponentCorpusService;
import de.bsnsoft.megarepo.repository.firewall.rule.impl.CvssThresholdRule;
import de.bsnsoft.megarepo.repository.firewall.rule.impl.KnownMaliciousRule;
import de.bsnsoft.megarepo.repository.firewall.rule.impl.LicenseRule;
import de.bsnsoft.megarepo.repository.firewall.rule.impl.MinimumAgeRule;
import de.bsnsoft.megarepo.repository.firewall.rule.impl.NamespaceConfusionRule;
import de.bsnsoft.megarepo.repository.firewall.rule.impl.TyposquatRule;
import de.bsnsoft.megarepo.repository.firewall.rule.impl.UnknownComponentRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * The lookup that replaced the engine's {@code switch}.
 *
 * <p>Everything the policy engine enforces passes through here, so this is where
 * the two structural promises are asserted: the set of rule types a build can
 * evaluate is exactly the set of beans on its classpath, and nothing a rule bean
 * does — including throwing — can turn into a match or into a clean pass.
 *
 * <p>The first test constructs every real rule bean rather than stubs, on
 * purpose. {@code implemented()} is served to the admin UI so a policy editor can
 * mark a rule type as "configurable but not yet enforced", and a build that adds
 * a rule bean without the catalogue noticing is exactly the drift that leaves an
 * operator switching on a rule that does nothing — or, worse, believing a rule
 * that <em>is</em> enforced is not.
 */
class FirewallRuleRegistryTest {

    @Test
    @DisplayName("every rule type has a bean except ADVISORY_MATCH, which is deliberately unimplemented")
    void theRealBeanSetIsTheImplementedSet() {
        FirewallRuleRegistry registry = new FirewallRuleRegistry(allRules());

        assertThat(registry.implemented()).containsExactlyInAnyOrder(
                FirewallRuleType.CVSS_THRESHOLD,
                FirewallRuleType.KNOWN_MALICIOUS,
                FirewallRuleType.LICENSE,
                FirewallRuleType.MIN_AGE,
                FirewallRuleType.UNKNOWN_COMPONENT,
                FirewallRuleType.TYPOSQUAT,
                FirewallRuleType.NAMESPACE_CONFUSION);
        assertThat(registry.isImplemented(FirewallRuleType.ADVISORY_MATCH))
                .as("CVSS_THRESHOLD and KNOWN_MALICIOUS cover what it would say; a policy row for it is inert")
                .isFalse();
    }

    @Test
    @DisplayName("exactly the two rules whose verdict changes on its own hold; the rest refuse outright")
    void onlyTheSelfResolvingRulesQuarantine() {
        FirewallRuleRegistry registry = new FirewallRuleRegistry(allRules());

        assertThat(holds(registry, FirewallRuleType.MIN_AGE))
                .as("the component gets older by itself")
                .isTrue();
        assertThat(holds(registry, FirewallRuleType.UNKNOWN_COMPONENT))
                .as("the data arrives by itself")
                .isTrue();

        assertThat(holds(registry, FirewallRuleType.CVSS_THRESHOLD)).isFalse();
        assertThat(holds(registry, FirewallRuleType.KNOWN_MALICIOUS)).isFalse();
        assertThat(holds(registry, FirewallRuleType.LICENSE)).isFalse();
        assertThat(holds(registry, FirewallRuleType.TYPOSQUAT)).isFalse();
        assertThat(holds(registry, FirewallRuleType.NAMESPACE_CONFUSION)).isFalse();

        assertThat(registry.find(FirewallRuleType.MIN_AGE).orElseThrow().quarantineReason())
                .isEqualTo(FirewallQuarantineReason.MIN_AGE_NOT_MET);
        assertThat(registry.find(FirewallRuleType.UNKNOWN_COMPONENT).orElseThrow().quarantineReason())
                .isEqualTo(FirewallQuarantineReason.UNKNOWN_COMPONENT);
    }

    @Test
    @DisplayName("two beans for one rule type fail startup rather than letting classpath order decide")
    void duplicateRuleTypesAreRejected() {
        assertThatThrownBy(() -> new FirewallRuleRegistry(
                List.of(new CvssThresholdRule(), new CvssThresholdRule())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CVSS_THRESHOLD")
                .as("a download allowed on Tuesday and denied on Wednesday is not a thing to debug later")
                .hasMessageContaining(CvssThresholdRule.class.getName());
    }

    @Test
    @DisplayName("a rule that declares no type fails startup as well")
    void aTypelessRuleIsRejected() {
        assertThatThrownBy(() -> new FirewallRuleRegistry(List.of(new FixedRule(null))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("declares no rule type");
    }

    @Test
    @DisplayName("an empty build reports no implemented types rather than failing")
    void anEmptyRegistryIsLegal() {
        assertThat(new FirewallRuleRegistry(List.of()).implemented()).isEmpty();
    }

    // ---------------------------------------------------------- dispatching

    @Test
    @DisplayName("a configured rule type with no bean is skipped, never treated as matched")
    void anUnimplementedTypeMatchesNothing() {
        FirewallRuleRegistry registry = new FirewallRuleRegistry(List.of(new CvssThresholdRule()));

        FirewallRuleOutcome outcome = registry.evaluate(
                purlContext(), settings(FirewallRuleType.LICENSE, true));

        assertThat(outcome.kind()).isEqualTo(FirewallRuleOutcome.Kind.NOT_MATCHED);
    }

    @Test
    @DisplayName("a disabled row is not dispatched at all")
    void aDisabledRuleIsNotDispatched() {
        FixedRule rule = new FixedRule(FirewallRuleType.LICENSE);
        FirewallRuleRegistry registry = new FirewallRuleRegistry(List.of(rule));

        assertThat(registry.evaluate(purlContext(), settings(FirewallRuleType.LICENSE, false)).kind())
                .isEqualTo(FirewallRuleOutcome.Kind.NOT_MATCHED);
        assertThat(rule.asked).isFalse();
        assertThat(registry.evaluate(purlContext(), null).kind())
                .isEqualTo(FirewallRuleOutcome.Kind.NOT_MATCHED);
    }

    @Test
    @DisplayName("a rule that throws is undecidable, and says which rule could not be evaluated")
    void aThrowingRuleIsContained() {
        FirewallRuleRegistry registry = new FirewallRuleRegistry(List.of(new ThrowingRule()));

        FirewallRuleOutcome outcome = registry.evaluate(
                purlContext(), settings(FirewallRuleType.LICENSE, true));

        assertThat(outcome.indeterminate())
                .as("treating it as matched denies downloads for a defect; as clean, it hides one")
                .isTrue();
        assertThat(outcome.reason()).contains("LICENSE");
    }

    @Test
    @DisplayName("a rule that answers null is read as 'found nothing', not as a match")
    void aNullOutcomeIsNotAMatch() {
        FirewallRuleRegistry registry = new FirewallRuleRegistry(List.of(new NullRule()));

        assertThat(registry.evaluate(purlContext(), settings(FirewallRuleType.LICENSE, true)).kind())
                .isEqualTo(FirewallRuleOutcome.Kind.NOT_MATCHED);
    }

    @Test
    @DisplayName("a component with no coordinates is only shown to the rule whose subject it is")
    void unidentifiedComponentsReachOnlyTheRuleThatWantsThem() {
        FixedRule coordinateBound = new FixedRule(FirewallRuleType.LICENSE);
        FirewallRuleRegistry registry = new FirewallRuleRegistry(List.of(coordinateBound));

        FirewallRuleOutcome outcome = registry.evaluate(
                hashContext(), settings(FirewallRuleType.LICENSE, true));

        assertThat(outcome.kind()).isEqualTo(FirewallRuleOutcome.Kind.NOT_MATCHED);
        assertThat(coordinateBound.asked)
                .as("running a purl-keyed rule against a digest is work spent on a foregone conclusion")
                .isFalse();

        FirewallRuleRegistry withUnknownComponent =
                new FirewallRuleRegistry(List.of(new UnknownComponentRule()));
        assertThat(withUnknownComponent.evaluate(
                        hashContext(), settings(FirewallRuleType.UNKNOWN_COMPONENT, true)).matched())
                .as("an unidentifiable artifact is exactly UNKNOWN_COMPONENT's subject")
                .isTrue();
    }

    // ------------------------------------------------------------------

    /** Every rule bean this build ships, as Spring would collect them. */
    private static List<FirewallRule> allRules() {
        ObjectProvider<ComponentFactsService> facts = provider();
        ComponentCorpusService corpus = mock(ComponentCorpusService.class);
        return List.of(
                new CvssThresholdRule(),
                new KnownMaliciousRule(),
                new LicenseRule(facts),
                new MinimumAgeRule(facts),
                new UnknownComponentRule(),
                new TyposquatRule(corpus),
                new NamespaceConfusionRule(corpus));
    }

    private static boolean holds(FirewallRuleRegistry registry, FirewallRuleType ruleType) {
        return registry.find(ruleType).orElseThrow().quarantineOnMatch();
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<ComponentFactsService> provider() {
        return mock(ObjectProvider.class);
    }

    private static FirewallRuleSettings settings(FirewallRuleType ruleType, boolean enabled) {
        return new FirewallRuleSettings(
                UUID.randomUUID(), ruleType, FirewallAction.BLOCK, java.util.Map.of(), enabled);
    }

    private static FirewallRuleContext purlContext() {
        return context(purl());
    }

    private static FirewallRuleContext hashContext() {
        return context(ComponentIdentity.Hash.sha256("e3b0c44298fc1c149afbf4c8996fb924"));
    }

    private static FirewallRuleContext context(ComponentIdentity identity) {
        return new FirewallRuleContext(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "maven-central",
                RepositoryType.PROXY,
                "com/acme/util/1.0.0/util-1.0.0.jar",
                identity,
                List.of(),
                null,
                new FirewallRepositorySettings(
                        FirewallMode.QUARANTINE, FirewallFailMode.FAIL_CLOSED, null, true),
                false,
                false,
                Instant.parse("2026-08-24T12:00:00Z"));
    }

    private static ComponentIdentity purl() {
        try {
            return new ComponentIdentity.Purl(
                    new PackageURL("maven", "com.acme", "util", "1.0.0", null, null));
        } catch (MalformedPackageURLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A rule that always matches, and remembers whether it was asked at all. */
    private static final class FixedRule implements FirewallRule {

        private final FirewallRuleType ruleType;
        private boolean asked;

        private FixedRule(FirewallRuleType ruleType) {
            this.ruleType = ruleType;
        }

        @Override
        public FirewallRuleType ruleType() {
            return ruleType;
        }

        @Override
        public FirewallRuleOutcome evaluate(FirewallRuleContext context, FirewallRuleSettings settings) {
            asked = true;
            return FirewallRuleOutcome.matched(new FirewallRuleViolation(
                    ruleType, settings.action(), "the stub rule matched", List.of()));
        }
    }

    /** A rule with a defect — the case the registry exists to contain. */
    private static final class ThrowingRule implements FirewallRule {

        @Override
        public FirewallRuleType ruleType() {
            return FirewallRuleType.LICENSE;
        }

        @Override
        public FirewallRuleOutcome evaluate(FirewallRuleContext context, FirewallRuleSettings settings) {
            throw new IllegalStateException("the license corpus is not loaded");
        }
    }

    /** A rule that forgets to answer. */
    private static final class NullRule implements FirewallRule {

        @Override
        public FirewallRuleType ruleType() {
            return FirewallRuleType.LICENSE;
        }

        @Override
        public FirewallRuleOutcome evaluate(FirewallRuleContext context, FirewallRuleSettings settings) {
            return null;
        }
    }
}
