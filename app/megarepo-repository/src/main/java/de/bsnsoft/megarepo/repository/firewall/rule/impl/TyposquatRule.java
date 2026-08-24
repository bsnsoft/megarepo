package de.bsnsoft.megarepo.repository.firewall.rule.impl;

import de.bsnsoft.megarepo.core.firewall.FirewallRuleType;
import de.bsnsoft.megarepo.repository.firewall.FirewallRuleViolation;
import de.bsnsoft.megarepo.repository.firewall.identity.ComponentIdentity;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRule;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRuleContext;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRuleOutcome;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRuleSettings;
import de.bsnsoft.megarepo.repository.firewall.rule.corpus.BoundedEditDistance;
import de.bsnsoft.megarepo.repository.firewall.rule.corpus.ComponentCorpusService;
import de.bsnsoft.megarepo.repository.firewall.rule.corpus.ComponentNameCorpus;
import de.bsnsoft.megarepo.repository.firewall.rule.corpus.CoordinatePattern;
import de.bsnsoft.megarepo.repository.firewall.rule.corpus.CorpusEntry;
import de.bsnsoft.megarepo.repository.firewall.rule.corpus.NameSkeleton;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Heuristic: a proxied package name that resembles a name this instance already
 * holds.
 *
 * <h2>What it actually claims</h2>
 *
 * Nothing about the package. It says that a name arriving from upstream is one
 * or two keystrokes away from a name this organisation already depends on, which
 * is the shape of a typosquat and also the shape of a coincidence. Every match
 * therefore states its evidence — which package, which distance, in how many
 * versions that package is held — because a build log that says "TYPOSQUAT" and
 * nothing else is an accusation nobody can check. The rule is expected to run as
 * {@code WARN}; a heuristic set to {@code BLOCK} on day one is a heuristic that
 * gets the whole firewall switched off.
 *
 * <h2>Two shapes of resemblance</h2>
 *
 * <ol>
 *   <li><b>The name</b>, within one namespace: {@code lodahs} for {@code lodash},
 *       {@code python-dateutil} for {@code pythondateutil}, {@code lodash} with a
 *       Cyrillic {@code о}. Comparison is between
 *       {@link NameSkeleton skeletons}, so a separator or homoglyph variant comes
 *       out as distance 0 — the strongest signal there is, because nobody types a
 *       Cyrillic letter by accident.</li>
 *   <li><b>The namespace</b>, with the name identical: {@code @babe1/core} for
 *       {@code @babel/core}, {@code com.acrne:util} for {@code com.acme:util}.
 *       Checked separately rather than by comparing whole coordinates, because a
 *       one-character namespace typo would drown in the length of a Maven
 *       groupId if both halves went into one distance.</li>
 * </ol>
 *
 * <h2>What it refuses to flag</h2>
 *
 * False positives are the failure mode that matters here: this rule fires on
 * ordinary, correct dependencies or it fires on nothing, and an operator only
 * needs a handful of the former to switch it off. Hence the guards, each of
 * which costs a real detection somewhere and is documented where it is applied:
 *
 * <ul>
 *   <li>hosted repositories are out of scope entirely — a colleague publishing
 *       {@code util} is not squatting anything ({@code context.fromProxy()});</li>
 *   <li>the requested package itself never matches, however often it appears in
 *       the corpus;</li>
 *   <li>short names are skipped: at four characters, everything is one edit from
 *       everything;</li>
 *   <li>an edit has to be earned by length — one edit per
 *       {@code charactersPerEdit} characters — so two edits do not qualify a
 *       six-letter name;</li>
 *   <li>names that differ only in a trailing digit are version siblings
 *       ({@code commons-lang} / {@code commons-lang3}), not squats;</li>
 *   <li>a package inside an established family is compared to its siblings and
 *       forgiven: with {@code lodash.get}, {@code lodash.set} and
 *       {@code lodash.merge} all present, the next {@code lodash.*} is a sibling,
 *       not an impostor;</li>
 *   <li>a namespace this instance already holds several packages under is
 *       established and never reported as a look-alike of another one — which is
 *       what keeps {@code @aws-cdk} from being flagged as a squat of
 *       {@code @aws-sdk};</li>
 *   <li>resemblance is only reported towards the better-established package. A
 *       squat that has been proxied once joins the corpus, and without this the
 *       rule would spend the rest of its life reporting the real package as a
 *       look-alike of the impostor.</li>
 * </ul>
 *
 * <h2>Configuration</h2>
 *
 * <pre>
 *   {"maxDistance": 1, "minPopularity": 1, "minLength": 5,
 *    "minFamilyMembers": 3, "charactersPerEdit": 4, "checkNamespace": true,
 *    "ignore": ["com.acme.*", "@internal/*"]}
 * </pre>
 *
 * <p>{@code maxDistance} is capped at 2. Three edits is not a typo, and the
 * candidate set grows with the square of it.
 */
@Component
public class TyposquatRule implements FirewallRule {

    static final int DEFAULT_MAX_DISTANCE = 1;
    static final int MAX_SUPPORTED_DISTANCE = 2;
    static final int DEFAULT_MIN_POPULARITY = 1;
    static final int DEFAULT_MIN_LENGTH = 5;
    static final int DEFAULT_MIN_FAMILY_MEMBERS = 3;
    static final int DEFAULT_CHARACTERS_PER_EDIT = 4;

    private final ComponentCorpusService corpusService;

    public TyposquatRule(ComponentCorpusService corpusService) {
        this.corpusService = corpusService;
    }

    @Override
    public FirewallRuleType ruleType() {
        return FirewallRuleType.TYPOSQUAT;
    }

    /**
     * Never quarantines. A name does not stop resembling another name by
     * waiting, so a release queue for this rule would fill with entries whose
     * verdict can only ever be changed by a human — which is what quarantine is
     * explicitly not for (wave plan §5.1).
     */
    @Override
    public boolean quarantineOnMatch() {
        return false;
    }

    @Override
    public FirewallRuleOutcome evaluate(FirewallRuleContext context, FirewallRuleSettings settings) {
        // Only packages arriving from upstream. A hosted upload or a hosted
        // serve is somebody in this organisation publishing under a name they
        // chose, and "your own package resembles your other package" is not a
        // finding.
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

        List<CoordinatePattern> ignored = CoordinatePattern.all(settings.textList("ignore", List.of()));
        if (CoordinatePattern.firstMatch(ignored, name, namespace, coordinate(purlType, namespace, name))
                != null) {
            return FirewallRuleOutcome.notMatched();
        }

        ComponentNameCorpus corpus = corpusService.corpus();
        if (corpus.isEmpty()) {
            // No corpus, no resemblance. Deliberately NOT_MATCHED rather than
            // INDETERMINATE: an undecidable outcome under FAIL_CLOSED would
            // quarantine every proxied download while a cache is cold or on an
            // instance that holds nothing yet, and the only thing a missing
            // corpus can cost this rule is a warning it would otherwise have
            // printed. NAMESPACE_CONFUSION reads the same state differently, and
            // says why.
            return FirewallRuleOutcome.notMatched();
        }

        int maxDistance = Math.clamp(
                (int) settings.number("maxDistance", DEFAULT_MAX_DISTANCE), 0, MAX_SUPPORTED_DISTANCE);
        int minPopularity = Math.max(1, (int) settings.number("minPopularity", DEFAULT_MIN_POPULARITY));
        int minLength = Math.max(1, (int) settings.number("minLength", DEFAULT_MIN_LENGTH));
        int minFamilyMembers = Math.max(
                2, (int) settings.number("minFamilyMembers", DEFAULT_MIN_FAMILY_MEMBERS));
        int charactersPerEdit = Math.max(
                1, (int) settings.number("charactersPerEdit", DEFAULT_CHARACTERS_PER_EDIT));

        String nameSkeleton = NameSkeleton.of(name);
        String namespaceSkeleton = NameSkeleton.of(namespace);

        // The length floor is about how much string there is to be confused
        // about, so it is measured on the whole coordinate: '@babe1/core' has a
        // four-letter name and nine characters of coordinate, and skipping it
        // because "core" is short would blind the rule to every scope squat in
        // npm. How far apart two names may be is a separate question, and one
        // that is answered per edit in earned() below.
        int coordinateLength = namespaceSkeleton.length() + nameSkeleton.length();
        Match best = coordinateLength < minLength ? null : closestName(
                corpus, purlType, namespace, namespaceSkeleton, name, nameSkeleton,
                maxDistance, minPopularity, minFamilyMembers, charactersPerEdit);

        if (best == null
                && settings.flag("checkNamespace", true)
                && namespaceSkeleton.length() >= minLength) {
            best = closestNamespace(
                    corpus, purlType, namespace, namespaceSkeleton, name, nameSkeleton,
                    maxDistance, minPopularity, minFamilyMembers, charactersPerEdit);
        }

        if (best == null) {
            return FirewallRuleOutcome.notMatched();
        }
        return FirewallRuleOutcome.matched(new FirewallRuleViolation(
                FirewallRuleType.TYPOSQUAT,
                settings.action(),
                best.reason(context, purlType, namespace, name),
                List.of()));
    }

    /** Case 1: same namespace, name a near miss. */
    private Match closestName(
            ComponentNameCorpus corpus,
            String purlType,
            String namespace,
            String namespaceSkeleton,
            String name,
            String nameSkeleton,
            int maxDistance,
            int minPopularity,
            int minFamilyMembers,
            int charactersPerEdit) {

        List<String> segments = NameSkeleton.segments(name);
        String firstSegment = segments.isEmpty() ? nameSkeleton : segments.get(0);
        // Does the requested name belong to a family this instance already has
        // several members of? If so, a one-edit difference in a later segment is
        // a sibling package and not an impostor. The family is looked up under
        // the requested name's *own* first segment: a squat misspells that
        // segment and therefore finds no family.
        boolean inEstablishedFamily = segments.size() > 1
                && corpus.namesInFamily(purlType, namespaceSkeleton, firstSegment) >= minFamilyMembers;

        // How established the requested package itself is. A squat joins the
        // corpus the moment it is first proxied, and without this every later
        // download of the *real* package would be reported as resembling the
        // impostor — the rule accusing the victim, permanently, in every build.
        int requestVersions = corpus.versionsOf(purlType, namespace, name);
        int floor = Math.max(minPopularity, requestVersions);

        Match[] best = new Match[1];
        corpus.forEachCandidateByLength(purlType, nameSkeleton.length(), maxDistance, candidate -> {
            if (candidate.versions() < floor) {
                return;
            }
            // The package itself, however many versions of it are stored.
            if (candidate.isSameCoordinateAs(namespace, name)) {
                return;
            }
            if (!candidate.namespaceSkeleton().equals(namespaceSkeleton)) {
                return;
            }
            if (NameSkeleton.differsOnlyInTrailingDigits(name, candidate.name())) {
                return;
            }
            if (inEstablishedFamily && candidate.firstSegmentSkeleton().equals(firstSegment)) {
                return;
            }
            int distance = BoundedEditDistance.between(
                    nameSkeleton, candidate.nameSkeleton(), maxDistance);
            if (distance == BoundedEditDistance.EXCEEDS_BOUND) {
                return;
            }
            if (!earned(distance, nameSkeleton.length(), charactersPerEdit)) {
                return;
            }
            best[0] = Match.better(best[0], new Match(candidate, distance));
        });
        return best[0];
    }

    /** Case 2: identical name, namespace a near miss. */
    private Match closestNamespace(
            ComponentNameCorpus corpus,
            String purlType,
            String namespace,
            String namespaceSkeleton,
            String name,
            String nameSkeleton,
            int maxDistance,
            int minPopularity,
            int minFamilyMembers,
            int charactersPerEdit) {

        // A namespace this instance already holds several packages under is one
        // it uses. Two established scopes may well be one character apart
        // (@aws-sdk and @aws-cdk both exist and both are legitimate), and
        // reporting the smaller one as a squat of the larger is exactly the
        // false positive that discredits the rule. Counted on the exact
        // namespace, so a look-alike cannot inherit the real one's standing.
        if (corpus.namesInNamespace(purlType, namespace) >= minFamilyMembers) {
            return null;
        }

        int floor = Math.max(minPopularity, corpus.versionsOf(purlType, namespace, name));

        Match best = null;
        for (CorpusEntry candidate : corpus.byNameSkeleton(purlType, nameSkeleton)) {
            // Same reasoning as in closestName: the better-established package is
            // the one a squat imitates, never the other way round.
            if (candidate.versions() < floor) {
                continue;
            }
            if (candidate.namespaceSkeleton().isEmpty()
                    || candidate.namespaceSkeleton().equals(namespaceSkeleton)) {
                continue;
            }
            if (candidate.isSameCoordinateAs(namespace, name)) {
                continue;
            }
            if (NameSkeleton.differsOnlyInTrailingDigits(namespace, candidate.namespace())) {
                continue;
            }
            int distance = BoundedEditDistance.between(
                    namespaceSkeleton, candidate.namespaceSkeleton(), maxDistance);
            if (distance == BoundedEditDistance.EXCEEDS_BOUND) {
                continue;
            }
            if (!earned(distance, namespaceSkeleton.length(), charactersPerEdit)) {
                continue;
            }
            best = Match.better(best, new Match(candidate, distance));
        }
        return best;
    }

    /**
     * Whether a name is long enough to have afforded that many edits.
     *
     * <p>One edit needs {@code charactersPerEdit} characters. Without this,
     * {@code maxDistance: 2} on a six-letter name compares things that share
     * two thirds of themselves by chance, and the rule turns into noise at
     * exactly the setting an operator reaches for when they want it to be
     * stricter.
     */
    private static boolean earned(int distance, int length, int charactersPerEdit) {
        return distance == 0 || (long) distance * charactersPerEdit <= length;
    }

    private static String coordinate(String purlType, String namespace, String name) {
        if (namespace == null || namespace.isEmpty()) {
            return name;
        }
        return "maven".equals(purlType) ? namespace + ":" + name : namespace + "/" + name;
    }

    /** The best resemblance found, and the text that explains it. */
    private record Match(CorpusEntry entry, int distance) {

        static Match better(Match current, Match candidate) {
            if (current == null) {
                return candidate;
            }
            if (candidate.distance != current.distance) {
                return candidate.distance < current.distance ? candidate : current;
            }
            if (candidate.entry.versions() != current.entry.versions()) {
                // More versions stored means more established, and a tie broken
                // by usage produces the more convincing evidence.
                return candidate.entry.versions() > current.entry.versions() ? candidate : current;
            }
            // Deterministic last resort: two evaluations of the same download
            // must not produce different violation texts.
            return candidate.entry.coordinate().compareTo(current.entry.coordinate()) < 0
                    ? candidate : current;
        }

        String reason(FirewallRuleContext context, String purlType, String namespace, String name) {
            String requested = coordinate(purlType, namespace, name);
            String known = entry.coordinate();
            String held = entry.versions() == 1
                    ? "1 version"
                    : entry.versions() + " versions";
            String source = entry.exampleRepository() == null
                    ? "this instance"
                    : "'" + entry.exampleRepository() + "'";
            String proximity = distance == 0
                    ? "differs only in look-alike characters or separators — both normalise to '%s'"
                            .formatted(normalised())
                    : "is %d edit%s away".formatted(distance, distance == 1 ? "" : "s");

            return ("Heuristic (name similarity, not a statement about the package): the %s of "
                    + "'%s', proxied through '%s', %s from '%s', which %s already holds in %s. "
                    + "A resemblance is a reason to check the coordinate, not proof of intent.")
                    .formatted(subject(namespace, name), requested, context.repositoryName(),
                            proximity, known, source, held);
        }

        /**
         * Which half of the coordinate the difference is in.
         *
         * <p>Read off the raw strings rather than from {@link #kind}: a
         * homoglyph in the namespace makes the two namespaces fold to the same
         * skeleton, so the resemblance is found by the name comparison while the
         * actual difference sits in the namespace. Reporting "the name of
         * '@babe1/core' differs from '@babel/core'" would send a developer
         * looking at the wrong four characters.
         */
        private String subject(String namespace, String name) {
            boolean sameName = NameSkeleton.plain(name).equals(NameSkeleton.plain(entry.name()));
            boolean sameNamespace =
                    NameSkeleton.plain(namespace).equals(NameSkeleton.plain(entry.namespace()));
            if (!sameName && !sameNamespace) {
                return "coordinate";
            }
            return sameName ? "namespace" : "name";
        }

        /** The shared normalised form both coordinates reduce to. */
        private String normalised() {
            return entry.namespaceSkeleton().isEmpty()
                    ? entry.nameSkeleton()
                    : entry.namespaceSkeleton() + "/" + entry.nameSkeleton();
        }
    }
}
