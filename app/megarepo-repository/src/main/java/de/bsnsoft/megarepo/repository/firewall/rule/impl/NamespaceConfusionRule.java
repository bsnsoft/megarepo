package de.bsnsoft.megarepo.repository.firewall.rule.impl;

import de.bsnsoft.megarepo.core.firewall.FirewallRuleType;
import de.bsnsoft.megarepo.repository.firewall.FirewallRuleViolation;
import de.bsnsoft.megarepo.repository.firewall.identity.ComponentIdentity;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRule;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRuleContext;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRuleOutcome;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRuleSettings;
import de.bsnsoft.megarepo.repository.firewall.rule.corpus.ComponentCorpusService;
import de.bsnsoft.megarepo.repository.firewall.rule.corpus.ComponentNameCorpus;
import de.bsnsoft.megarepo.repository.firewall.rule.corpus.CoordinatePattern;
import de.bsnsoft.megarepo.repository.firewall.rule.corpus.CorpusEntry;
import de.bsnsoft.megarepo.repository.firewall.rule.corpus.NameSkeleton;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * An internal coordinate must never be answered by an upstream proxy.
 *
 * <h2>The attack</h2>
 *
 * A build asks for {@code com.acme:billing}, a package that only exists inside
 * this organisation. It should be served by a hosted repository. If a proxy
 * answers instead — because somebody registered {@code com.acme} on a public
 * registry, or because a group's member order changed, or because the internal
 * repository was briefly offline — then the build has just installed a stranger's
 * code under a name it trusts. Nothing about the artifact is wrong; the
 * <em>source</em> is. That is dependency confusion, and this rule is the whole of
 * MegaRepo's answer to it.
 *
 * <p>Which is why it reads {@code repositoryType} rather than anything about the
 * package: through a group the resolving member decides, and the resolving
 * member being a proxy is the finding. A hosted repository serving the same
 * coordinate is the correct behaviour and never matches — the rule cannot make
 * an instance stop serving its own packages.
 *
 * <h2>Where "internal" comes from</h2>
 *
 * <ol>
 *   <li><b>Configured patterns</b>, {@code internalNamespaces}. Explicit, exact,
 *       and what an operator should be using: {@code ["com.acme", "com.acme.*",
 *       "@acme/*"]}. A pattern is matched against the namespace, against the
 *       {@code namespace/name} coordinate, and — for ecosystems that have no
 *       namespace at all, where an internal package is only recognisable by its
 *       name — against the name.</li>
 *   <li><b>Derived from hosted repositories</b>, when
 *       {@code deriveFromHostedRepositories} is on: any namespace this instance
 *       publishes under is treated as internal, and for namespace-less
 *       ecosystems any exact package name it publishes.</li>
 * </ol>
 *
 * <h2>Why derivation is off by default</h2>
 *
 * Because "hosted" does not mean "ours". Maven instances very commonly carry a
 * {@code third-party} hosted repository holding vendor jars that were never on
 * Maven Central, and npm instances carry re-published forks. Deriving from those
 * would declare {@code org.apache.commons} internal and warn on every proxied
 * download of it — a rule that fires on the entire dependency graph, on the day
 * it is switched on, teaches its operator to switch it back off. Derivation is
 * the convenient setting for an instance whose hosted repositories genuinely
 * hold only its own code, and the operator is the one who knows that.
 *
 * <h2>Configuration</h2>
 *
 * <pre>
 *   {"internalNamespaces": ["com.acme", "com.acme.*", "@acme/*"],
 *    "deriveFromHostedRepositories": false,
 *    "ignore": ["com.acme.public.*"]}
 * </pre>
 */
@Component
public class NamespaceConfusionRule implements FirewallRule {

    private final ComponentCorpusService corpusService;

    public NamespaceConfusionRule(ComponentCorpusService corpusService) {
        this.corpusService = corpusService;
    }

    @Override
    public FirewallRuleType ruleType() {
        return FirewallRuleType.NAMESPACE_CONFUSION;
    }

    /**
     * Never quarantines. An internal coordinate arriving from upstream does not
     * become acceptable by waiting — either the operator adds an exemption or
     * the internal repository is fixed, and both are human acts. Quarantine is
     * reserved for verdicts that change on their own (wave plan §5.1).
     */
    @Override
    public boolean quarantineOnMatch() {
        return false;
    }

    @Override
    public FirewallRuleOutcome evaluate(FirewallRuleContext context, FirewallRuleSettings settings) {
        if (!context.fromProxy() || context.upload()) {
            return FirewallRuleOutcome.notMatched();
        }
        ComponentIdentity.Purl identity = context.purl();
        if (identity == null) {
            return FirewallRuleOutcome.notMatched();
        }

        String purlType = NameSkeleton.plain(identity.purl().getType());
        String namespace = identity.purl().getNamespace();
        String name = identity.purl().getName();
        if (name == null || name.isBlank()) {
            return FirewallRuleOutcome.notMatched();
        }
        String coordinate = coordinate(purlType, namespace, name);

        List<CoordinatePattern> ignored =
                CoordinatePattern.all(settings.textList("ignore", List.of()));
        if (CoordinatePattern.firstMatch(ignored, namespace, coordinate, name) != null) {
            return FirewallRuleOutcome.notMatched();
        }

        List<CoordinatePattern> internal =
                CoordinatePattern.all(settings.textList("internalNamespaces", List.of()));
        // A namespace-less ecosystem is judged on its name; a namespaced one is
        // not, so that a pattern meant for pypi names cannot start matching
        // Maven artifactIds under somebody else's groupId.
        CoordinatePattern configured = namespace == null || namespace.isBlank()
                ? CoordinatePattern.firstMatch(internal, name)
                : CoordinatePattern.firstMatch(internal, namespace, coordinate);
        if (configured != null) {
            return FirewallRuleOutcome.matched(violation(settings, context, coordinate,
                    "namespace '%s' is configured as internal (pattern '%s')".formatted(
                            namespace == null || namespace.isBlank() ? name : namespace,
                            configured.raw())));
        }

        if (!settings.flag("deriveFromHostedRepositories", false)) {
            return FirewallRuleOutcome.notMatched();
        }

        ComponentNameCorpus corpus = corpusService.corpus();
        if (corpus.neverLoaded()) {
            // Unlike TYPOSQUAT, this rule is not a hint: with derivation on, the
            // set of internal namespaces IS the rule, and answering "nothing
            // internal here" from an unread corpus would silently disable a
            // dependency-confusion defence during exactly the window after a
            // restart. The engine's fail mode is the right place for that
            // decision, and quarantine entries written under
            // EVALUATION_INCOMPLETE are re-evaluated on their own once the scan
            // finishes.
            return FirewallRuleOutcome.indeterminate(
                    "the list of namespaces published in this instance's hosted repositories "
                            + "has not been read yet");
        }

        CorpusEntry hosted = namespace == null || namespace.isBlank()
                ? corpus.hostedCoordinate(purlType, null, name)
                : corpus.hostedInNamespace(purlType, namespace);
        if (hosted != null) {
            String where = hosted.exampleRepository() == null
                    ? "a hosted repository of this instance"
                    : "hosted repository '" + hosted.exampleRepository() + "'";
            return FirewallRuleOutcome.matched(violation(settings, context, coordinate,
                    "%s publishes '%s' under the same %s (derived from hosted repositories)"
                            .formatted(where, hosted.coordinate(),
                                    namespace == null || namespace.isBlank()
                                            ? "package name" : "namespace")));
        }
        return FirewallRuleOutcome.notMatched();
    }

    private FirewallRuleViolation violation(
            FirewallRuleSettings settings,
            FirewallRuleContext context,
            String coordinate,
            String evidence) {
        String reason = ("Heuristic (coordinate origin, not a statement about the package): '%s' was "
                + "served by proxy repository '%s', but %s. An internal coordinate answered from "
                + "upstream is what dependency confusion looks like — check which repository should "
                + "hold this package before allowing it.")
                .formatted(coordinate, context.repositoryName(), evidence);
        return new FirewallRuleViolation(
                FirewallRuleType.NAMESPACE_CONFUSION, settings.action(), reason, List.of());
    }

    private static String coordinate(String purlType, String namespace, String name) {
        if (namespace == null || namespace.isBlank()) {
            return name;
        }
        return "maven".equals(purlType) ? namespace + ":" + name : namespace + "/" + name;
    }
}
