package de.bsnsoft.megarepo.repository.firewall.rule.impl;

import com.github.packageurl.PackageURL;
import de.bsnsoft.megarepo.core.firewall.FirewallAction;
import de.bsnsoft.megarepo.core.firewall.FirewallFailMode;
import de.bsnsoft.megarepo.core.firewall.FirewallMode;
import de.bsnsoft.megarepo.core.firewall.FirewallRuleType;
import de.bsnsoft.megarepo.core.repository.RepositoryType;
import de.bsnsoft.megarepo.repository.firewall.FirewallRepositorySettings;
import de.bsnsoft.megarepo.repository.firewall.identity.ComponentIdentity;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRuleContext;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRuleOutcome;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRuleSettings;
import de.bsnsoft.megarepo.repository.firewall.rule.corpus.ComponentCorpusService;
import de.bsnsoft.megarepo.repository.firewall.rule.corpus.ComponentNameCorpus;
import de.bsnsoft.megarepo.repository.firewall.rule.corpus.CorpusFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * The dependency-confusion rule: an internal coordinate answered by a proxy.
 *
 * <p>Both sources of "internal" are exercised — the configured patterns and the
 * namespaces derived from hosted repositories — and so is the case the rule must
 * never touch: the same coordinate served by the hosted repository it belongs
 * to.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NamespaceConfusionRuleTest {

    private static final UUID REPOSITORY_ID = UUID.randomUUID();

    @Mock private ComponentCorpusService corpusService;

    private NamespaceConfusionRule rule;

    @BeforeEach
    void setUp() {
        rule = new NamespaceConfusionRule(corpusService);
        givenCorpus(hostedCorpus());
    }

    private static ComponentNameCorpus hostedCorpus() {
        return CorpusFixtures.corpus()
                .add("maven", "com.acme", "billing", 2, true, "maven-internal")
                .add("pypi", null, "acme-internal", 1, true, "pypi-internal")
                .proxied("maven", "org.apache.commons", "commons-lang3", 3)
                .build();
    }

    // ------------------------------------------------------ configured patterns

    @Test
    @DisplayName("a configured internal namespace arriving from a proxy matches")
    void configuredNamespace() {
        FirewallRuleOutcome outcome = evaluate(
                maven("com.acme", "billing"), internal("com.acme", "com.acme.*"));

        assertThat(outcome.matched()).isTrue();
        assertThat(outcome.violation().ruleType()).isEqualTo(FirewallRuleType.NAMESPACE_CONFUSION);
        assertThat(outcome.violation().reason())
                .contains("com.acme:billing")
                .contains("maven-central")
                .contains("com.acme")
                .contains("dependency confusion");
    }

    @Test
    @DisplayName("a prefix pattern covers sub-namespaces and the namespace itself")
    void prefixPattern() {
        FirewallRuleSettings settings = internal("com.acme.*");

        assertThat(evaluate(maven("com.acme.billing", "api"), settings).matched()).isTrue();
        assertThat(evaluate(maven("com.acme", "billing"), settings).matched()).isTrue();
        assertThat(evaluate(maven("com.acmex", "billing"), settings).matched()).isFalse();
    }

    @Test
    @DisplayName("an npm scope pattern matches the scope, not somebody else's package of that name")
    void scopePattern() {
        FirewallRuleSettings settings = internal("@acme/*");

        assertThat(evaluate(npm("@acme", "tools"), settings).matched()).isTrue();
        assertThat(evaluate(npm("@other", "tools"), settings).matched()).isFalse();
        assertThat(evaluate(npm(null, "tools"), settings).matched()).isFalse();
    }

    @Test
    @DisplayName("an ecosystem without namespaces is matched on the package name")
    void namelessEcosystem() {
        FirewallRuleSettings settings = internal("acme-*");

        assertThat(evaluate(pypi("acme-internal"), settings).matched()).isTrue();
        assertThat(evaluate(pypi("requests"), settings).matched()).isFalse();
    }

    @Test
    @DisplayName("a name pattern does not reach into somebody else's namespace")
    void namePatternDoesNotCrossNamespaces() {
        // 'billing' as a pattern is about a namespace-less coordinate; it must
        // not start matching every artifactId called billing on Maven Central.
        assertThat(evaluate(maven("org.other", "billing"), internal("billing")).matched()).isFalse();
    }

    @Test
    @DisplayName("an unconfigured rule matches nothing at all")
    void unconfigured() {
        FirewallRuleSettings empty =
                FirewallRuleSettings.of(FirewallRuleType.NAMESPACE_CONFUSION, FirewallAction.WARN);

        assertThat(evaluate(maven("com.acme", "billing"), empty).matched()).isFalse();
    }

    @Test
    @DisplayName("an ignored coordinate is served even though its namespace is internal")
    void ignoreList() {
        FirewallRuleSettings settings = FirewallRuleSettings.of(
                FirewallRuleType.NAMESPACE_CONFUSION, FirewallAction.WARN,
                Map.of("internalNamespaces", List.of("com.acme.*"),
                        "ignore", List.of("com.acme.public.*")));

        assertThat(evaluate(maven("com.acme.public", "sdk"), settings).matched()).isFalse();
        assertThat(evaluate(maven("com.acme.billing", "api"), settings).matched()).isTrue();
    }

    // ---------------------------------------------------------------- origin

    @Test
    @DisplayName("the same coordinate from its own hosted repository is untouched")
    void hostedIsNeverConfusion() {
        FirewallRuleContext context = context(
                maven("com.acme", "billing"), RepositoryType.HOSTED, false);

        assertThat(rule.evaluate(context, internal("com.acme.*")).matched()).isFalse();
    }

    @Test
    @DisplayName("publishing an internal package into a hosted repository is not confusion either")
    void uploadIsNeverConfusion() {
        FirewallRuleContext context = context(
                maven("com.acme", "billing"), RepositoryType.PROXY, true);

        assertThat(rule.evaluate(context, internal("com.acme.*")).matched()).isFalse();
    }

    // ------------------------------------------------------------- derivation

    @Test
    @DisplayName("derivation is off unless it is switched on")
    void derivationIsOptIn() {
        // The corpus knows com.acme is published here, and without the flag that
        // is deliberately not enough: a hosted 'third-party' repository would
        // otherwise declare half of Maven Central internal.
        FirewallRuleSettings noDerivation =
                FirewallRuleSettings.of(FirewallRuleType.NAMESPACE_CONFUSION, FirewallAction.WARN);

        assertThat(evaluate(maven("com.acme", "other"), noDerivation).matched()).isFalse();
    }

    @Test
    @DisplayName("with derivation on, a namespace published here must not arrive from upstream")
    void derivedNamespace() {
        FirewallRuleOutcome outcome = evaluate(maven("com.acme", "other"), derived());

        assertThat(outcome.matched()).isTrue();
        assertThat(outcome.violation().reason())
                .contains("maven-internal")
                .contains("com.acme:billing")
                .contains("derived from hosted repositories");
    }

    @Test
    @DisplayName("derivation covers namespace-less ecosystems by exact package name")
    void derivedName() {
        assertThat(evaluate(pypi("acme-internal"), derived()).matched()).isTrue();
        assertThat(evaluate(pypi("acme-external"), derived()).matched()).isFalse();
    }

    @Test
    @DisplayName("a namespace only ever proxied is not internal")
    void proxiedNamespaceIsNotInternal() {
        assertThat(evaluate(maven("org.apache.commons", "commons-lang3"), derived()).matched())
                .isFalse();
    }

    @Test
    @DisplayName("a look-alike of an internal namespace is not treated as the internal one")
    void lookAlikeIsNotTheNamespace() {
        // That resemblance is TYPOSQUAT's finding. Answering it here would mean
        // a squatted namespace inherits the exemptions and the wording of the
        // real one.
        assertThat(evaluate(maven("com.acrne", "billing"), derived()).matched()).isFalse();
    }

    @Test
    @DisplayName("before the hosted namespaces have been read, the rule says so instead of guessing")
    void corpusNotLoadedYet() {
        givenCorpus(ComponentNameCorpus.notLoadedYet());

        FirewallRuleOutcome outcome = evaluate(maven("com.acme", "other"), derived());

        assertThat(outcome.indeterminate()).isTrue();
        assertThat(outcome.reason()).contains("hosted repositories");
        assertThat(outcome.matched()).isFalse();
    }

    @Test
    @DisplayName("a corpus that loaded and found nothing is an answer, not a pending state")
    void loadedButEmpty() {
        givenCorpus(CorpusFixtures.corpus().build());

        FirewallRuleOutcome outcome = evaluate(maven("com.acme", "other"), derived());

        assertThat(outcome.indeterminate()).isFalse();
        assertThat(outcome.matched()).isFalse();
    }

    @Test
    @DisplayName("configured patterns decide before the corpus is consulted at all")
    void patternsDoNotNeedTheCorpus() {
        givenCorpus(ComponentNameCorpus.notLoadedYet());

        FirewallRuleSettings settings = FirewallRuleSettings.of(
                FirewallRuleType.NAMESPACE_CONFUSION, FirewallAction.BLOCK,
                Map.of("internalNamespaces", List.of("com.acme.*"),
                        "deriveFromHostedRepositories", true));

        FirewallRuleOutcome outcome = evaluate(maven("com.acme", "billing"), settings);

        assertThat(outcome.matched()).isTrue();
        assertThat(outcome.violation().action()).isEqualTo(FirewallAction.BLOCK);
    }

    // ---------------------------------------------------------- non-vacuity

    @Test
    @DisplayName("non-vacuity: the fixture that produces every negative above also produces matches")
    void nonVacuity() {
        FirewallRuleSettings settings = internal("com.acme.*");

        assertThat(evaluate(maven("org.other", "billing"), settings).matched()).isFalse();
        assertThat(evaluate(maven("com.acme", "billing"), settings).matched()).isTrue();

        assertThat(rule.evaluate(context(maven("com.acme", "billing"), RepositoryType.HOSTED, false),
                settings).matched()).isFalse();
        assertThat(rule.evaluate(context(maven("com.acme", "billing"), RepositoryType.PROXY, false),
                settings).matched()).isTrue();

        assertThat(evaluate(maven("com.acme", "other"),
                FirewallRuleSettings.of(FirewallRuleType.NAMESPACE_CONFUSION, FirewallAction.WARN))
                .matched()).isFalse();
        assertThat(evaluate(maven("com.acme", "other"), derived()).matched()).isTrue();
    }

    @Test
    @DisplayName("the rule holds nothing in quarantine — only a person can resolve this one")
    void neverQuarantines() {
        assertThat(rule.quarantineOnMatch()).isFalse();
        assertThat(rule.ruleType()).isEqualTo(FirewallRuleType.NAMESPACE_CONFUSION);
        assertThat(rule.appliesToUnidentifiedComponents()).isFalse();
    }

    // ------------------------------------------------------------- fixtures

    private void givenCorpus(ComponentNameCorpus corpus) {
        when(corpusService.corpus()).thenReturn(corpus);
    }

    private FirewallRuleOutcome evaluate(PackageURL purl, FirewallRuleSettings settings) {
        return rule.evaluate(context(purl, RepositoryType.PROXY, false), settings);
    }

    private FirewallRuleContext context(PackageURL purl, RepositoryType type, boolean upload) {
        return new FirewallRuleContext(
                REPOSITORY_ID,
                "maven-central",
                type,
                "/" + purl.getName(),
                new ComponentIdentity.Purl(purl),
                List.of(),
                null,
                new FirewallRepositorySettings(
                        FirewallMode.QUARANTINE, FirewallFailMode.FAIL_CLOSED, null, true),
                upload,
                false,
                Instant.now());
    }

    private static FirewallRuleSettings internal(String... namespaces) {
        return FirewallRuleSettings.of(
                FirewallRuleType.NAMESPACE_CONFUSION, FirewallAction.WARN,
                Map.of("internalNamespaces", List.of(namespaces)));
    }

    private static FirewallRuleSettings derived() {
        return FirewallRuleSettings.of(
                FirewallRuleType.NAMESPACE_CONFUSION, FirewallAction.WARN,
                Map.of("deriveFromHostedRepositories", true));
    }

    private static PackageURL maven(String namespace, String name) {
        return CorpusFixtures.purl("maven", namespace, name, "1.0.0");
    }

    private static PackageURL npm(String namespace, String name) {
        return CorpusFixtures.purl("npm", namespace, name, "1.0.0");
    }

    private static PackageURL pypi(String name) {
        return CorpusFixtures.purl("pypi", null, name, "1.0.0");
    }
}
