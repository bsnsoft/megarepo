package de.bsnsoft.megarepo.repository.firewall.rule;

import de.bsnsoft.megarepo.core.repository.RepositoryType;
import de.bsnsoft.megarepo.repository.advisory.AdvisoryFinding;
import de.bsnsoft.megarepo.repository.advisory.MatchConfidence;
import de.bsnsoft.megarepo.repository.firewall.FirewallRepositorySettings;
import de.bsnsoft.megarepo.repository.firewall.facts.ComponentFacts;
import de.bsnsoft.megarepo.repository.firewall.identity.ComponentIdentity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Everything one rule may look at while judging one component.
 *
 * <h2>Why the context is a value and not a service handle</h2>
 *
 * A rule receives facts, not the ability to fetch them. Everything in this
 * record has already been read from the local database by the engine, once, for
 * all rules — which is what keeps a policy with six rules from costing six times
 * the queries, and what makes "no network on the request thread" a property of
 * the engine rather than a promise each rule has to keep separately.
 *
 * <p>Rules that need a collaborator of their own — a typosquat corpus, the list
 * of hosted namespaces — inject it as a Spring bean; they are beans themselves.
 * The rule of thumb is that anything varying <em>per evaluation</em> belongs
 * here, and anything varying per deployment is a dependency.
 *
 * @param repositoryId the repository whose policy is being applied. Through a
 *     group this is the <em>member</em> that resolved the artifact, never the
 *     group: the component lives in the member, and so does the configuration
 *     that governs it
 * @param repositoryName its name, for messages a developer reads in a build log
 * @param repositoryType whether the artifact came from a proxy or a hosted
 *     repository. {@code NAMESPACE_CONFUSION} is the rule that needs it — the
 *     whole finding is "this internal-looking coordinate arrived from the
 *     internet"
 * @param path the artifact path that was requested
 * @param identity what the component was identified as; never null, but may be
 *     {@link ComponentIdentity.Hash} or {@link ComponentIdentity.Unidentified},
 *     which is exactly what {@code UNKNOWN_COMPONENT} is about
 * @param findings advisories naming the component, already merged and
 *     confidence-labelled; empty means the local advisory store had nothing,
 *     which is a finding of its own for {@code UNKNOWN_COMPONENT} and no finding
 *     at all for the advisory-driven rules
 * @param facts declared publication date and licenses; never null, and its state
 *     may well be {@code UNKNOWN} — see {@link FirewallRuleOutcome.Kind#INDETERMINATE}
 * @param settings the repository's resolved firewall configuration
 * @param upload true when this is an upload into a hosted repository rather than
 *     a download. Hosted uploads are evaluated too, and a rule may reasonably
 *     read differently in that direction — refusing to publish something is not
 *     the same act as refusing to serve it
 * @param preExisting whether the component was already stored in this repository
 *     before enforcement was switched on. Rules still match and are still
 *     recorded for such a component; the engine is what declines to deny it
 * @param evaluatedAt the clock for this evaluation, passed rather than read so
 *     that every rule in one pass agrees about "now" and a test can pick it
 */
public record FirewallRuleContext(
        UUID repositoryId,
        String repositoryName,
        RepositoryType repositoryType,
        String path,
        ComponentIdentity identity,
        List<AdvisoryFinding> findings,
        ComponentFacts facts,
        FirewallRepositorySettings settings,
        boolean upload,
        boolean preExisting,
        Instant evaluatedAt) {

    public FirewallRuleContext {
        Objects.requireNonNull(identity, "identity must not be null");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt must not be null");
        findings = findings == null ? List.of() : List.copyOf(findings);
        facts = facts == null ? ComponentFacts.unknown(identity.key()) : facts;
    }

    /** The component's purl or hash key. */
    public String componentKey() {
        return identity.key();
    }

    /** Whether the component has package coordinates an advisory feed could name. */
    public boolean hasPurl() {
        return identity instanceof ComponentIdentity.Purl;
    }

    /** The purl identity, or null when the component has no coordinates. */
    public ComponentIdentity.Purl purl() {
        return identity instanceof ComponentIdentity.Purl p ? p : null;
    }

    /** Whether the artifact was proxied from upstream rather than published here. */
    public boolean fromProxy() {
        return repositoryType == RepositoryType.PROXY;
    }

    /**
     * The findings a rule at this confidence level may act on.
     *
     * <p>Every advisory-driven rule filters this way, and doing it here rather
     * than in each rule is what keeps the "BLOCK demands an EXACT match" decision
     * in one place. Getting it wrong in one rule would reintroduce precisely the
     * CPE false positives the customer reported about the V8 firewall.
     */
    public List<AdvisoryFinding> findingsAtLeast(MatchConfidence minConfidence) {
        List<AdvisoryFinding> qualifying = new ArrayList<>();
        for (AdvisoryFinding finding : findings) {
            // MatchConfidence is declared strongest first, so "at least as
            // strong as" is compareTo <= 0.
            if (finding.confidence().compareTo(minConfidence) <= 0) {
                qualifying.add(finding);
            }
        }
        return List.copyOf(qualifying);
    }
}
