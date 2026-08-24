package de.bsnsoft.megarepo.rest.controller;

import de.bsnsoft.megarepo.core.firewall.FirewallAction;
import de.bsnsoft.megarepo.core.firewall.FirewallRuleType;
import de.bsnsoft.megarepo.rest.dto.firewall.FirewallRuleTypeXO;
import de.bsnsoft.megarepo.rest.dto.firewall.FirewallRuleTypeXO.ConfigField;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * What every rule type is called, what it reads out of {@code config}, and what
 * an operator should know before switching it to BLOCK.
 *
 * <h2>Why a hand-written catalogue rather than reflection</h2>
 *
 * The parameters a rule reads are {@code settings.number("maxDistance", …)}
 * calls inside a method — there is nothing to reflect over, and adding an
 * annotation to every accessor would put the description of a rule somewhere a
 * rule author has to remember to update anyway. Keeping it here instead makes
 * one file the single answer to "what can this rule be configured with", which
 * is also the file the API's test compares against the implementations.
 *
 * <h2>It describes types this build may not implement</h2>
 *
 * Deliberately. A policy row of an unimplemented type is skipped, never
 * enforced, and the editor has to be able to render it as "not enforced by this
 * version" rather than as a working switch — which it cannot do for a type the
 * server never mentions. {@code implemented} comes from
 * {@code FirewallRuleRegistry}, not from here.
 *
 * <h2>What is not in it</h2>
 *
 * {@link FirewallRuleType#ADVISORY_MATCH} — it is not a policy rule but the
 * Phase 1 observation that some advisory names the component, written into the
 * violation log because that column is NOT NULL. Offering it in a policy editor
 * would invite somebody to configure an action for a finding that nothing
 * evaluates.
 */
final class FirewallRuleCatalog {

    /** {@code minConfidence}, which every advisory-driven rule reads. */
    private static final ConfigField MIN_CONFIDENCE = new ConfigField(
            "minConfidence",
            "enum",
            "Minimum match confidence",
            "How firmly an advisory has to name the component before this rule acts on it. "
                    + "EXACT means the advisory carried the package coordinates; HEURISTIC also "
                    + "accepts CPE-derived matches, which can name an unrelated product with a "
                    + "similar name. Defaults to EXACT for a BLOCK rule and HEURISTIC for a WARN "
                    + "rule — warn about everything, block only on what was actually identified.",
            null,
            false,
            List.of("EXACT", "HEURISTIC"));

    private static final ConfigField IGNORE = new ConfigField(
            "ignore",
            "stringList",
            "Exceptions",
            "Coordinates this rule never reports, as \"namespace:name\" patterns with optional "
                    + "trailing wildcards.",
            List.of(),
            false,
            List.of());

    private static final Map<FirewallRuleType, FirewallRuleTypeXO> CATALOG = build();

    private FirewallRuleCatalog() {}

    /**
     * The rule types a policy editor may offer, in {@link FirewallRuleType}
     * declaration order — which puts the two heuristics last, where they belong.
     */
    static List<FirewallRuleType> offered() {
        return List.copyOf(CATALOG.keySet());
    }

    /**
     * The static description of one type.
     *
     * @return the entry, or null for a type that is not offered
     *     ({@link FirewallRuleType#ADVISORY_MATCH})
     */
    static FirewallRuleTypeXO describe(FirewallRuleType ruleType) {
        return CATALOG.get(ruleType);
    }

    private static Map<FirewallRuleType, FirewallRuleTypeXO> build() {
        Map<FirewallRuleType, FirewallRuleTypeXO> catalog = new EnumMap<>(FirewallRuleType.class);

        catalog.put(FirewallRuleType.CVSS_THRESHOLD, new FirewallRuleTypeXO(
                FirewallRuleType.CVSS_THRESHOLD,
                "CVSS threshold",
                "Matches when an advisory naming this component scores at or above a CVSS "
                        + "severity you choose.",
                false, // implemented — overwritten by the registry
                false, // heuristic
                false, // quarantines — overwritten by the bean when there is one
                false, // requiresComponentFacts
                FirewallAction.BLOCK,
                null,
                List.of(
                        new ConfigField(
                                "minScore",
                                "number",
                                "Minimum CVSS score",
                                "The severity at which a component is refused. 9.0 is CRITICAL and "
                                        + "denies what nobody wants to consume; 7.0 covers a large "
                                        + "share of a typical dependency tree and turns switching "
                                        + "the firewall on into breaking the build.",
                                9.0,
                                false,
                                List.of()),
                        MIN_CONFIDENCE)));

        catalog.put(FirewallRuleType.KNOWN_MALICIOUS, new FirewallRuleTypeXO(
                FirewallRuleType.KNOWN_MALICIOUS,
                "Known malicious",
                "Matches when an advisory source flags the component as malicious rather than "
                        + "vulnerable — a package that exists to steal credentials has no CVSS "
                        + "score to compare against.",
                false, // implemented — overwritten by the registry
                false, // heuristic
                false, // quarantines — overwritten by the bean when there is one
                false, // requiresComponentFacts
                FirewallAction.BLOCK,
                null,
                List.of(
                        new ConfigField(
                                "idPrefixes",
                                "stringList",
                                "Advisory id prefixes",
                                "Which advisory ids count as a malice report. OSV publishes these "
                                        + "as MAL- entries.",
                                List.of("MAL-"),
                                false,
                                List.of()),
                        MIN_CONFIDENCE)));

        catalog.put(FirewallRuleType.MIN_AGE, new FirewallRuleTypeXO(
                FirewallRuleType.MIN_AGE,
                "Minimum age",
                "Holds a component that was published more recently than the age you set, so a "
                        + "compromised release has time to be found and withdrawn before it "
                        + "reaches a build.",
                false, // implemented — overwritten by the registry
                false, // heuristic
                true, // quarantines — overwritten by the bean when there is one
                true, // requiresComponentFacts
                FirewallAction.BLOCK,
                "Needs the publication date from the component facts resolver. With "
                        + "megarepo.firewall.facts.enabled off this rule can never decide, and a "
                        + "fail-closed repository holds everything it is asked for.",
                List.of(new ConfigField(
                        "minAge",
                        "duration",
                        "Minimum age",
                        "How old a release has to be before it may be served. Accepts an ISO-8601 "
                                + "duration (\"P7D\") or a plain number of days (7).",
                        "P7D",
                        false,
                        List.of()))));

        catalog.put(FirewallRuleType.UNKNOWN_COMPONENT, new FirewallRuleTypeXO(
                FirewallRuleType.UNKNOWN_COMPONENT,
                "Unknown component",
                "Matches when no advisory source has any entry for the component, or when the "
                        + "artifact carries no package coordinates at all.",
                false, // implemented — overwritten by the registry
                false, // heuristic
                true, // quarantines — overwritten by the bean when there is one
                false, // requiresComponentFacts
                FirewallAction.WARN,
                "Matches practically every component a proxy serves: most packages have no "
                        + "advisory of any kind, and \"nothing is known about it\" is the normal "
                        + "case rather than the exception. No default policy carries this rule. "
                        + "Set it to BLOCK on a single repository first and read the quarantine "
                        + "queue before going further.",
                List.of(
                        new ConfigField(
                                "allowUnidentifiedFormats",
                                "stringList",
                                "Formats without coordinates",
                                "Formats whose artifacts legitimately have no package coordinates "
                                        + "— \"raw\" is the usual one. Listing a format here stops "
                                        + "this rule quarantining every file in it.",
                                List.of(),
                                false,
                                List.of()),
                        new ConfigField(
                                "includeHostedComponents",
                                "boolean",
                                "Also judge hosted components",
                                "Off by default: a package a colleague published into a hosted "
                                        + "repository is expected to have no advisory, and holding "
                                        + "it says nothing about its safety.",
                                false,
                                false,
                                List.of()),
                        MIN_CONFIDENCE)));

        catalog.put(FirewallRuleType.LICENSE, new FirewallRuleTypeXO(
                FirewallRuleType.LICENSE,
                "License",
                "Matches on the license the component declares in its own metadata. Declared "
                        + "metadata only — MegaRepo does not read licenses out of file contents.",
                false, // implemented — overwritten by the registry
                false, // heuristic
                false, // quarantines — overwritten by the bean when there is one
                true, // requiresComponentFacts
                FirewallAction.WARN,
                "Needs the declared license from the component facts resolver, and an SPDX "
                        + "expression it cannot parse is reported as undecidable rather than as a "
                        + "match.",
                List.of(
                        new ConfigField(
                                "allowed",
                                "stringList",
                                "Allowed licenses",
                                "SPDX ids that are acceptable. When set, anything else matches. "
                                        + "Compared case-insensitively.",
                                List.of(),
                                false,
                                List.of()),
                        new ConfigField(
                                "denied",
                                "stringList",
                                "Denied licenses",
                                "SPDX ids that are not acceptable. Can be combined with the "
                                        + "allow list.",
                                List.of(),
                                false,
                                List.of()),
                        new ConfigField(
                                "allowUndeclared",
                                "boolean",
                                "Allow components that declare no license",
                                "On by default. Switching it off matches every component whose "
                                        + "metadata names no license, which for some ecosystems is "
                                        + "a large share of them.",
                                true,
                                false,
                                List.of()))));

        catalog.put(FirewallRuleType.TYPOSQUAT, new FirewallRuleTypeXO(
                FirewallRuleType.TYPOSQUAT,
                "Typosquat (heuristic)",
                "Compares an incoming proxied coordinate against the packages this instance "
                        + "already holds and reports names that are a near-miss of one of them.",
                false, // implemented — overwritten by the registry
                true, // heuristic
                false, // quarantines — overwritten by the bean when there is one
                false, // requiresComponentFacts
                FirewallAction.WARN,
                "A heuristic, not a statement of fact: it reports that a name resembles another "
                        + "name, which a legitimate fork or a renamed package does too. Leave it "
                        + "on WARN and read what it finds — a heuristic set to BLOCK on day one is "
                        + "a heuristic that gets the whole firewall switched off.",
                List.of(
                        new ConfigField(
                                "maxDistance",
                                "number",
                                "Maximum edit distance",
                                "How many single-character edits may separate the incoming name "
                                        + "from a known one before it stops being suspicious.",
                                1,
                                false,
                                List.of()),
                        new ConfigField(
                                "minPopularity",
                                "number",
                                "Minimum versions of the resembled package",
                                "How established a package has to be before a near-miss of its "
                                        + "name counts. Raising it silences one-off names that "
                                        + "were themselves only downloaded once.",
                                1,
                                false,
                                List.of()),
                        new ConfigField(
                                "minLength",
                                "number",
                                "Minimum name length",
                                "Short names are close to each other by accident; below this "
                                        + "length nothing is reported.",
                                5,
                                false,
                                List.of()),
                        new ConfigField(
                                "minFamilyMembers",
                                "number",
                                "Minimum family size for a shared prefix",
                                "Names that only differ inside an established family of packages "
                                        + "with the same prefix need at least this many members "
                                        + "before the family is treated as one.",
                                3,
                                false,
                                List.of()),
                        new ConfigField(
                                "charactersPerEdit",
                                "number",
                                "Characters per permitted edit",
                                "Scales the distance with the name length, so a long name is not "
                                        + "judged as harshly as a short one.",
                                4,
                                false,
                                List.of()),
                        new ConfigField(
                                "checkNamespace",
                                "boolean",
                                "Also compare the namespace",
                                "Whether a near-miss in the group or scope counts as well as one "
                                        + "in the package name.",
                                true,
                                false,
                                List.of()),
                        IGNORE)));

        catalog.put(FirewallRuleType.NAMESPACE_CONFUSION, new FirewallRuleTypeXO(
                FirewallRuleType.NAMESPACE_CONFUSION,
                "Namespace confusion (heuristic)",
                "Reports an internal coordinate arriving from an upstream proxy — the shape of a "
                        + "dependency-confusion attack, where a public package claims a namespace "
                        + "the organisation publishes itself.",
                false, // implemented — overwritten by the registry
                true, // heuristic
                false, // quarantines — overwritten by the bean when there is one
                false, // requiresComponentFacts
                FirewallAction.WARN,
                "A heuristic: an internal namespace that is also published publicly on purpose "
                        + "matches it every time. Leave it on WARN until the reported namespaces "
                        + "have been read once.",
                List.of(
                        new ConfigField(
                                "internalNamespaces",
                                "stringList",
                                "Internal namespaces",
                                "Namespaces this organisation publishes itself, as patterns with "
                                        + "optional trailing wildcards (\"com.acme\", "
                                        + "\"com.acme.*\").",
                                List.of(),
                                false,
                                List.of()),
                        new ConfigField(
                                "deriveFromHostedRepositories",
                                "boolean",
                                "Also derive them from hosted repositories",
                                "Adds the namespaces already present in hosted repositories to the "
                                        + "list above, so a new internal package is covered without "
                                        + "an edit here.",
                                false,
                                false,
                                List.of()),
                        IGNORE)));

        return catalog;
    }
}
