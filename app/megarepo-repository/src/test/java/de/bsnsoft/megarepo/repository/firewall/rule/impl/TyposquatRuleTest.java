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
 * The typosquat heuristic against a synthetic corpus.
 *
 * <p>The corpus below is the one every test shares, and it is built to contain
 * the awkward cases rather than only the convenient ones: a package family
 * ({@code lodash.*}), two legitimate scopes one character apart
 * ({@code @aws-sdk} / {@code @aws-cdk}), a version-suffixed name and packages in
 * three ecosystems. The negative tests are only worth anything because the same
 * fixture produces matches — see {@link #nonVacuity()}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TyposquatRuleTest {

    private static final UUID REPOSITORY_ID = UUID.randomUUID();

    @Mock private ComponentCorpusService corpusService;

    private TyposquatRule rule;

    @BeforeEach
    void setUp() {
        rule = new TyposquatRule(corpusService);
        givenCorpus(standardCorpus());
    }

    private static ComponentNameCorpus standardCorpus() {
        return CorpusFixtures.corpus()
                .proxied("npm", null, "lodash", 3)
                .proxied("npm", null, "lodash.get", 1)
                .proxied("npm", null, "lodash.merge", 1)
                .proxied("npm", null, "lodash.pick", 1)
                .proxied("npm", null, "express", 2)
                .proxied("npm", null, "minimist", 1)
                .proxied("npm", "@babel", "core", 4)
                .proxied("npm", "@babel", "preset-env", 1)
                .proxied("npm", "@babel", "runtime", 1)
                .proxied("npm", "@aws-sdk", "core", 2)
                .proxied("npm", "@aws-cdk", "core", 1)
                .proxied("npm", "@aws-cdk", "aws-s3", 1)
                .proxied("npm", "@aws-cdk", "aws-lambda", 1)
                .proxied("pypi", null, "python-dateutil", 2)
                .proxied("pypi", null, "acme-client", 1)
                .proxied("maven", "com.acme", "util", 2)
                .build();
    }

    // ---------------------------------------------------------------- matches

    @Test
    @DisplayName("a transposed pair of letters matches the package it resembles")
    void transposition() {
        FirewallRuleOutcome outcome = evaluate(npm(null, "lodahs"), warn());

        assertThat(outcome.matched()).isTrue();
        assertThat(outcome.violation().ruleType()).isEqualTo(FirewallRuleType.TYPOSQUAT);
        assertThat(outcome.violation().action()).isEqualTo(FirewallAction.WARN);
    }

    @Test
    @DisplayName("a digit standing in for a letter is a zero-distance look-alike")
    void homoglyph() {
        FirewallRuleOutcome outcome = evaluate(npm(null, "l0dash"), warn());

        assertThat(outcome.matched()).isTrue();
        assertThat(outcome.violation().reason())
                .contains("look-alike characters or separators")
                .contains("'lodash'");
    }

    @Test
    @DisplayName("a separator variant of a known name matches")
    void separatorVariant() {
        FirewallRuleOutcome outcome = evaluate(pypi("pythondateutil"), warn());

        assertThat(outcome.matched()).isTrue();
        assertThat(outcome.violation().reason()).contains("python-dateutil");
    }

    @Test
    @DisplayName("a near-miss Maven groupId under an identical artifactId matches")
    void namespaceNearMiss() {
        FirewallRuleOutcome outcome = evaluate(maven("com.acmi", "util"), warn());

        assertThat(outcome.matched()).isTrue();
        assertThat(outcome.violation().reason())
                .contains("the namespace of")
                .contains("com.acme:util")
                .contains("1 edit away");
    }

    @Test
    @DisplayName("an npm scope with a digit in it matches even though the package name is short")
    void scopeLookAlike() {
        FirewallRuleOutcome outcome = evaluate(npm("@babe1", "core"), warn());

        assertThat(outcome.matched()).isTrue();
        assertThat(outcome.violation().reason())
                .contains("the namespace of")
                .contains("@babel/core");
    }

    @Test
    @DisplayName("the violation states its evidence: which package, how far, how well known")
    void evidenceIsInTheText() {
        FirewallRuleOutcome outcome = evaluate(npm(null, "lodahs"), warn());

        assertThat(outcome.violation().reason())
                .as("a developer reading a build log has to be able to check the claim")
                .contains("Heuristic")
                .contains("'lodahs'")
                .contains("'lodash'")
                .contains("1 edit away")
                .contains("3 versions")
                .contains("central-proxy")
                .contains("npm-central");
        assertThat(outcome.violation().advisoryIds()).isEmpty();
    }

    @Test
    @DisplayName("a BLOCK rule produces a BLOCK violation — the action is the policy's, not the rule's")
    void actionPassesThrough() {
        FirewallRuleOutcome outcome = evaluate(npm(null, "lodahs"),
                FirewallRuleSettings.of(FirewallRuleType.TYPOSQUAT, FirewallAction.BLOCK));

        assertThat(outcome.violation().action()).isEqualTo(FirewallAction.BLOCK);
    }

    // -------------------------------------------------------------- negatives

    @Test
    @DisplayName("the package itself is not a typosquat of itself")
    void theRealPackage() {
        assertThat(evaluate(npm(null, "lodash"), warn()).matched()).isFalse();
        assertThat(evaluate(npm("@babel", "core"), warn()).matched()).isFalse();
        assertThat(evaluate(maven("com.acme", "util"), warn()).matched()).isFalse();
    }

    @Test
    @DisplayName("a hosted repository is out of scope — publishing under a name is not squatting")
    void hostedIsNotSquatting() {
        FirewallRuleContext context = context(npm(null, "lodahs"), RepositoryType.HOSTED, false);

        assertThat(rule.evaluate(context, warn()).matched()).isFalse();
    }

    @Test
    @DisplayName("an upload is not a typosquat either")
    void uploadIsNotSquatting() {
        FirewallRuleContext context = context(npm(null, "lodahs"), RepositoryType.PROXY, true);

        assertThat(rule.evaluate(context, warn()).matched()).isFalse();
    }

    @Test
    @DisplayName("a sibling in an established family is not an impostor")
    void siblingInFamily() {
        // lodash.set against a corpus holding lodash.get, lodash.merge and
        // lodash.pick: one edit from lodash.get, and obviously the same author's
        // next utility.
        assertThat(evaluate(npm(null, "lodash.set"), warn()).matched()).isFalse();
    }

    @Test
    @DisplayName("two legitimate scopes one character apart do not accuse each other")
    void establishedScopes() {
        // @aws-cdk is one edit from @aws-sdk, and this instance holds three
        // packages under it.
        assertThat(evaluate(npm("@aws-cdk", "core"), warn()).matched()).isFalse();
    }

    @Test
    @DisplayName("a version-suffixed name is a sibling release line, not a squat")
    void versionSuffix() {
        assertThat(evaluate(pypi("acme-client2"), warn()).matched()).isFalse();
    }

    @Test
    @DisplayName("a squat that has been cached does not turn into an accusation against the original")
    void theVictimIsNotTheSuspect() {
        // Once l0dash has been proxied once it is in the corpus too, and a naive
        // comparison would report every later download of lodash as resembling
        // it — the rule accusing the victim in every build, for ever.
        ComponentNameCorpus corpus = CorpusFixtures.corpus()
                .proxied("npm", null, "lodash", 3)
                .proxied("npm", null, "l0dash", 1)
                .build();
        givenCorpus(corpus);

        assertThat(evaluate(npm(null, "lodash"), warn()).matched()).isFalse();
        assertThat(evaluate(npm(null, "l0dash"), warn()).matched())
                .as("the impostor is still reported")
                .isTrue();
    }

    @Test
    @DisplayName("a short name is left alone — at three characters everything is one edit away")
    void shortNames() {
        ComponentNameCorpus corpus = CorpusFixtures.corpus()
                .proxied("npm", null, "util", 2)
                .build();
        givenCorpus(corpus);

        assertThat(evaluate(npm(null, "utl"), warn()).matched()).isFalse();
    }

    @Test
    @DisplayName("a name in another ecosystem is not a resemblance")
    void ecosystemsAreSeparate() {
        assertThat(evaluate(pypi("lodahs"), warn()).matched()).isFalse();
        assertThat(evaluate(maven("com.acme", "lodahs"), warn()).matched()).isFalse();
    }

    @Test
    @DisplayName("an ignored coordinate is skipped whatever it resembles")
    void ignoreList() {
        FirewallRuleSettings settings = FirewallRuleSettings.of(
                FirewallRuleType.TYPOSQUAT, FirewallAction.WARN, Map.of("ignore", List.of("loda*")));

        assertThat(evaluate(npm(null, "lodahs"), settings).matched()).isFalse();
    }

    @Test
    @DisplayName("no corpus means no finding, never an undecidable evaluation")
    void withoutCorpus() {
        givenCorpus(ComponentNameCorpus.notLoadedYet());
        FirewallRuleOutcome outcome = evaluate(npm(null, "lodahs"), warn());

        // FAIL_CLOSED plus INDETERMINATE would quarantine every proxied download
        // while the corpus is cold; a missing corpus can only cost this rule a
        // warning it would otherwise have printed.
        assertThat(outcome.matched()).isFalse();
        assertThat(outcome.indeterminate()).isFalse();
    }

    @Test
    @DisplayName("a component without coordinates is not this rule's business")
    void withoutPurl() {
        FirewallRuleContext context = new FirewallRuleContext(
                REPOSITORY_ID, "npm-central", RepositoryType.PROXY, "/some/file.bin",
                ComponentIdentity.Hash.sha256("abcdef"), List.of(), null,
                repositorySettings(), false, false, Instant.now());

        assertThat(rule.evaluate(context, warn()).matched()).isFalse();
    }

    // ------------------------------------------------------------ thresholds

    @Test
    @DisplayName("the distance threshold is honoured on both sides of it")
    void distanceBoundary() {
        // minimalist is two edits from minimist.
        assertThat(evaluate(npm(null, "minimalist"), warn()).matched()).isFalse();

        FirewallRuleSettings twoEdits = FirewallRuleSettings.of(
                FirewallRuleType.TYPOSQUAT, FirewallAction.WARN, Map.of("maxDistance", 2));
        assertThat(evaluate(npm(null, "minimalist"), twoEdits).matched()).isTrue();
    }

    @Test
    @DisplayName("an edit has to be earned by length, so two edits do not qualify a short name")
    void editsAreEarned() {
        ComponentNameCorpus corpus = CorpusFixtures.corpus()
                .proxied("npm", null, "helmet", 2)
                .build();
        givenCorpus(corpus);

        FirewallRuleSettings twoEdits = FirewallRuleSettings.of(
                FirewallRuleType.TYPOSQUAT, FirewallAction.WARN, Map.of("maxDistance", 2));
        // halmut is two edits from helmet, over six characters — one edit per
        // four characters is not met.
        assertThat(evaluate(npm(null, "halmut"), twoEdits).matched()).isFalse();

        FirewallRuleSettings looser = FirewallRuleSettings.of(
                FirewallRuleType.TYPOSQUAT, FirewallAction.WARN,
                Map.of("maxDistance", 2, "charactersPerEdit", 3));
        assertThat(evaluate(npm(null, "halmut"), looser).matched()).isTrue();
    }

    @Test
    @DisplayName("maxDistance is capped: three edits is not a typo however it is configured")
    void distanceIsCapped() {
        ComponentNameCorpus corpus = CorpusFixtures.corpus()
                .proxied("npm", null, "abcdefghijkl", 2)
                .build();
        givenCorpus(corpus);

        FirewallRuleSettings absurd = FirewallRuleSettings.of(
                FirewallRuleType.TYPOSQUAT, FirewallAction.WARN, Map.of("maxDistance", 99));
        assertThat(evaluate(npm(null, "abcdefghxyzl"), absurd).matched()).isFalse();
    }

    @Test
    @DisplayName("minPopularity keeps a name this instance barely uses from being the reference")
    void popularityThreshold() {
        FirewallRuleSettings strict = FirewallRuleSettings.of(
                FirewallRuleType.TYPOSQUAT, FirewallAction.WARN, Map.of("minPopularity", 3));
        // lodash is held in three versions, minimist in one.
        assertThat(evaluate(npm(null, "lodahs"), strict).matched()).isTrue();
        assertThat(evaluate(npm(null, "minimsit"), strict).matched()).isFalse();
        assertThat(evaluate(npm(null, "minimsit"), warn()).matched()).isTrue();
    }

    @Test
    @DisplayName("unreadable configuration falls back to the defaults and does not match wildly")
    void malformedConfig() {
        FirewallRuleSettings nonsense = FirewallRuleSettings.of(
                FirewallRuleType.TYPOSQUAT, FirewallAction.BLOCK,
                Map.of("maxDistance", "not-a-number", "minLength", "", "ignore", ""));

        // Default behaviour: the transposition still matches, an unrelated
        // package still does not.
        assertThat(evaluate(npm(null, "lodahs"), nonsense).matched()).isTrue();
        assertThat(evaluate(npm(null, "webpack"), nonsense).matched()).isFalse();
    }

    @Test
    @DisplayName("checkNamespace can be switched off without switching the rule off")
    void namespaceCheckIsOptional() {
        FirewallRuleSettings nameOnly = FirewallRuleSettings.of(
                FirewallRuleType.TYPOSQUAT, FirewallAction.WARN, Map.of("checkNamespace", false));

        assertThat(evaluate(maven("com.acmi", "util"), nameOnly).matched()).isFalse();
        assertThat(evaluate(npm(null, "lodahs"), nameOnly).matched()).isTrue();
    }

    // ---------------------------------------------------------- non-vacuity

    @Test
    @DisplayName("non-vacuity: every guard above is checked against a corpus that does produce matches")
    void nonVacuity() {
        // Each pair is (silenced, still flagged) against the *same* corpus and
        // the same settings. Without the right-hand column, every negative test
        // above would also pass if evaluate() simply always returned
        // NOT_MATCHED.
        assertThat(evaluate(npm(null, "lodash"), warn()).matched()).isFalse();
        assertThat(evaluate(npm(null, "lodahs"), warn()).matched()).isTrue();

        assertThat(evaluate(npm(null, "lodash.set"), warn()).matched()).isFalse();
        givenCorpus(CorpusFixtures.corpus().proxied("npm", null, "lodash.get", 1).build());
        assertThat(evaluate(npm(null, "lodash.set"), warn()).matched())
                .as("with no family around it, the same name is reported")
                .isTrue();

        givenCorpus(standardCorpus());
        assertThat(evaluate(npm("@aws-cdk", "core"), warn()).matched()).isFalse();
        givenCorpus(CorpusFixtures.corpus().proxied("npm", "@aws-sdk", "core", 2).build());
        assertThat(evaluate(npm("@aws-cdk", "core"), warn()).matched())
                .as("with @aws-cdk unknown to this instance, the scope is reported")
                .isTrue();

        givenCorpus(standardCorpus());
        assertThat(evaluate(pypi("acme-client2"), warn()).matched()).isFalse();
        assertThat(evaluate(pypi("acme-c1ient"), warn()).matched())
                .as("the same name with a look-alike character instead of a version suffix")
                .isTrue();
    }

    @Test
    @DisplayName("the rule holds nothing in quarantine — a resemblance does not resolve by waiting")
    void neverQuarantines() {
        assertThat(rule.quarantineOnMatch()).isFalse();
        assertThat(rule.ruleType()).isEqualTo(FirewallRuleType.TYPOSQUAT);
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
                "npm-central",
                type,
                "/" + purl.getName(),
                new ComponentIdentity.Purl(purl),
                List.of(),
                null,
                repositorySettings(),
                upload,
                false,
                Instant.now());
    }

    private static FirewallRepositorySettings repositorySettings() {
        return new FirewallRepositorySettings(
                FirewallMode.QUARANTINE, FirewallFailMode.FAIL_OPEN, null, true);
    }

    private static FirewallRuleSettings warn() {
        return FirewallRuleSettings.of(FirewallRuleType.TYPOSQUAT, FirewallAction.WARN);
    }

    private static PackageURL npm(String namespace, String name) {
        return CorpusFixtures.purl("npm", namespace, name, "1.0.0");
    }

    private static PackageURL pypi(String name) {
        return CorpusFixtures.purl("pypi", null, name, "1.0.0");
    }

    private static PackageURL maven(String namespace, String name) {
        return CorpusFixtures.purl("maven", namespace, name, "1.0.0");
    }
}
