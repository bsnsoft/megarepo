package de.bsnsoft.megarepo.repository.firewall.rule.impl;

import de.bsnsoft.megarepo.core.firewall.FirewallFactsState;
import de.bsnsoft.megarepo.core.firewall.FirewallRuleType;
import de.bsnsoft.megarepo.repository.firewall.FirewallRuleViolation;
import de.bsnsoft.megarepo.repository.firewall.facts.ComponentFacts;
import de.bsnsoft.megarepo.repository.firewall.facts.ComponentFactsService;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRule;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRuleContext;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRuleOutcome;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRuleSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/**
 * {@code LICENSE}: the licenses a component <em>declares</em> against the
 * policy's allow and deny lists.
 *
 * <h2>Declared metadata only</h2>
 *
 * The customer's scope boundary, not an implementation shortcut: the rule reads
 * what the POM, the {@code package.json}, the {@code METADATA} or the registry
 * document states, and never what the files contain. Scanning source for license
 * headers is a different product with a different false-positive profile, and
 * {@code firewall_component_facts.license_source} carries a CHECK constraint that
 * keeps the promise enforceable rather than merely intended.
 *
 * <h2>Configuration</h2>
 *
 * <pre>
 *   {"denied":  ["GPL-3.0-only", "AGPL-3.0-only"]}
 *   {"allowed": ["MIT", "Apache-2.0", "BSD-3-Clause"]}
 *   {"allowUndeclared": false}     default true
 * </pre>
 *
 * A rule with neither list and {@code allowUndeclared} left on has nothing to
 * enforce and is inert on every path — including the indeterminate one, so that
 * an empty {@code LICENSE} row in a policy cannot quarantine a fail-closed
 * repository.
 *
 * <h2>How a declaration is compared</h2>
 *
 * <ul>
 *   <li>Comparison is <b>exact string, case-insensitive, whitespace-collapsed</b>.
 *       There is no SPDX alias table: {@code "Apache License 2.0"} and
 *       {@code "Apache-2.0"} are different strings, and inventing a mapping
 *       between the identifiers an ecosystem publishes and the ones an operator
 *       typed would decide compliance questions on our guesses. The violation
 *       reason quotes the declaration verbatim so the string can be added to the
 *       list.</li>
 *   <li>An <b>SPDX expression</b> is evaluated rather than compared: {@code OR} is
 *       a genuine choice, so {@code (MIT OR GPL-3.0-only)} passes a policy that
 *       denies GPL — the consumer may take the MIT arm — while
 *       {@code MIT AND GPL-3.0-only} does not. Operators are recognised only in
 *       upper case, which is what SPDX requires and what keeps the prose
 *       {@code "Eclipse Public License and Common Public License"} a single
 *       license name rather than a conjunction.</li>
 *   <li><b>Several declared entries all have to pass.</b> A POM listing two
 *       licences most often means both apply, and reading a bare list as a choice
 *       would let one permissive entry clear a component the policy denies. A
 *       real choice is expressible, and expressed, as {@code OR}.</li>
 *   <li>An expression the rule <b>cannot parse</b> — unbalanced brackets, a
 *       dangling operator — is {@link FirewallRuleOutcome.Kind#INDETERMINATE} and
 *       never a match. A malformed declaration is a fact about the metadata, and
 *       guessing at it in the blocking direction denies a build over a typo
 *       somebody else made. A deny-list hit elsewhere in the same component still
 *       wins, because that verdict does not depend on the part we could not
 *       read.</li>
 * </ul>
 *
 * <h2>Three ways to have no license, and why only one of them matches</h2>
 *
 * <ul>
 *   <li>Facts {@code UNKNOWN}/{@code PENDING} — nobody has looked yet.
 *       {@code INDETERMINATE}, and a background resolution is requested.</li>
 *   <li>Facts {@code RESOLVED} with an empty list — the package declares no
 *       license. A fact in its own right, and the one case
 *       {@code allowUndeclared} governs.</li>
 *   <li>Facts {@code UNAVAILABLE} — the metadata could not be read at all.
 *       {@code NOT_MATCHED}: "we could not look" is not the same statement as
 *       "the package declares nothing", and a firewall fault must serve the
 *       artifact rather than deny it.</li>
 * </ul>
 *
 * <h2>Not a quarantine</h2>
 *
 * {@link #quarantineOnMatch()} stays false. A license verdict does not change by
 * waiting, so a release queue for it would be a list of decisions nobody can make
 * — the exemption workflow is where a license exception belongs.
 */
@Component
public class LicenseRule implements FirewallRule {

    private static final Logger log = LoggerFactory.getLogger(LicenseRule.class);

    /** Config key: the only acceptable licenses. Empty means "no allow list". */
    static final String CONFIG_ALLOWED = "allowed";

    /** Config key: licenses that are never acceptable. Wins over the allow list. */
    static final String CONFIG_DENIED = "denied";

    /** Config key: whether a package that declares no license at all is acceptable. */
    static final String CONFIG_ALLOW_UNDECLARED = "allowUndeclared";

    /**
     * Resolved once at construction and possibly null — see
     * {@link MinimumAgeRule}: an installation built without the facts store still
     * starts, and this rule answers {@code INDETERMINATE} for everything
     * unresolved.
     */
    private final ComponentFactsService facts;

    public LicenseRule(ObjectProvider<ComponentFactsService> facts) {
        this.facts = facts.getIfAvailable();
    }

    @Override
    public FirewallRuleType ruleType() {
        return FirewallRuleType.LICENSE;
    }

    @Override
    public FirewallRuleOutcome evaluate(FirewallRuleContext context, FirewallRuleSettings settings) {
        Set<String> allowed = keys(settings.textList(CONFIG_ALLOWED, List.of()));
        Set<String> denied = keys(settings.textList(CONFIG_DENIED, List.of()));
        boolean allowUndeclared = settings.flag(CONFIG_ALLOW_UNDECLARED, true);

        if (allowed.isEmpty() && denied.isEmpty() && allowUndeclared) {
            return FirewallRuleOutcome.notMatched();
        }
        // The registry already filters these out; repeated because a component
        // with no coordinates has no package metadata either, and reporting
        // INDETERMINATE for one would hold it for a fact that cannot exist.
        if (!context.hasPurl()) {
            return FirewallRuleOutcome.notMatched();
        }

        ComponentFacts componentFacts = context.facts();
        if (componentFacts.isIndeterminate()) {
            requestResolution(context);
            return FirewallRuleOutcome.indeterminate(
                    "the declared licenses of %s have not been resolved yet"
                            .formatted(context.componentKey()));
        }

        List<String> declared = declarations(componentFacts);
        if (declared.isEmpty()) {
            if (componentFacts.state() == FirewallFactsState.UNAVAILABLE) {
                return FirewallRuleOutcome.notMatched();
            }
            return allowUndeclared
                    ? FirewallRuleOutcome.notMatched()
                    : FirewallRuleOutcome.matched(violation(
                            settings, "declares no license, and the policy requires one"));
        }

        List<String> unacceptable = new ArrayList<>();
        List<String> unreadable = new ArrayList<>();
        boolean deniedHit = false;

        for (String declaration : declared) {
            LicenseExpression expression = LicenseExpression.parse(declaration);
            if (expression == null) {
                unreadable.add(declaration);
                continue;
            }
            if (!expression.isAcceptable(allowed, denied)) {
                unacceptable.add(declaration);
                deniedHit |= expression.mentionsAny(denied);
            }
        }

        if (!unacceptable.isEmpty()) {
            String list = String.join(", ", unacceptable);
            String reason = deniedHit
                    ? "declares %s, which the policy denies".formatted(list)
                    : "declares %s, which is not on the policy's list of allowed licenses"
                            .formatted(list);
            return FirewallRuleOutcome.matched(violation(settings, reason));
        }
        if (!unreadable.isEmpty()) {
            return FirewallRuleOutcome.indeterminate(
                    "the declared license expression %s could not be read"
                            .formatted(String.join(", ", unreadable)));
        }
        return FirewallRuleOutcome.notMatched();
    }

    private static FirewallRuleViolation violation(FirewallRuleSettings settings, String reason) {
        return new FirewallRuleViolation(
                FirewallRuleType.LICENSE, settings.action(), reason, List.of());
    }

    /** Declared entries with the blanks a resolver may have stored dropped. */
    private static List<String> declarations(ComponentFacts facts) {
        List<String> declared = new ArrayList<>();
        for (String entry : facts.declaredLicenses()) {
            if (entry != null && !entry.isBlank()) {
                declared.add(entry.trim());
            }
        }
        return declared;
    }

    /** See {@link MinimumAgeRule}: enqueues, never fetches, never throws. */
    private void requestResolution(FirewallRuleContext context) {
        if (facts == null) {
            return;
        }
        try {
            facts.requestResolution(context.identity());
        } catch (RuntimeException e) {
            log.debug("Could not queue a facts resolution for {}", context.componentKey(), e);
        }
    }

    private static Set<String> keys(Collection<String> values) {
        Set<String> keys = new LinkedHashSet<>();
        for (String value : values) {
            String key = normalize(value);
            if (!key.isEmpty()) {
                keys.add(key);
            }
        }
        return keys;
    }

    /** Comparison key: whitespace collapsed, upper case, nothing else changed. */
    private static String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }

    /**
     * One declared license entry, as an SPDX-shaped expression tree.
     *
     * <p>Small on purpose. It understands brackets, {@code AND} and {@code OR},
     * and treats everything else — including {@code WITH} exceptions and
     * free-text license names — as one opaque identifier to be compared against
     * the policy's lists. That is enough to answer the question the rule asks and
     * short enough to be obviously correct; a full SPDX expression implementation
     * would be a licence-compliance product rather than one firewall rule.
     */
    static final class LicenseExpression {

        private final Node root;

        private LicenseExpression(Node root) {
            this.root = root;
        }

        /**
         * @return the parsed expression, or null when the text is not an
         *     expression this rule can read — the {@code INDETERMINATE} case
         */
        static LicenseExpression parse(String text) {
            try {
                Parser parser = new Parser(tokenize(text));
                Node root = parser.expression();
                parser.expectEnd();
                return new LicenseExpression(root);
            } catch (MalformedExpression e) {
                return null;
            }
        }

        /**
         * Whether the policy permits this declaration.
         *
         * @param allowed allowed license keys; empty means "no allow list", i.e.
         *     anything not denied passes
         * @param denied denied license keys; a denied identifier is never
         *     acceptable even when it also appears in the allow list
         */
        boolean isAcceptable(Set<String> allowed, Set<String> denied) {
            Predicate<String> acceptable =
                    key -> !denied.contains(key) && (allowed.isEmpty() || allowed.contains(key));
            return root.acceptable(acceptable);
        }

        /** Whether any identifier in this declaration is on the deny list — wording only. */
        boolean mentionsAny(Set<String> keys) {
            Set<String> atoms = new LinkedHashSet<>();
            root.collectAtoms(atoms);
            for (String atom : atoms) {
                if (keys.contains(atom)) {
                    return true;
                }
            }
            return false;
        }

        private sealed interface Node {
            boolean acceptable(Predicate<String> atomAcceptable);

            void collectAtoms(Collection<String> into);
        }

        private record Atom(String key) implements Node {
            @Override
            public boolean acceptable(Predicate<String> atomAcceptable) {
                return atomAcceptable.test(key);
            }

            @Override
            public void collectAtoms(Collection<String> into) {
                into.add(key);
            }
        }

        /** A choice: one acceptable arm is enough. */
        private record Any(List<Node> parts) implements Node {
            @Override
            public boolean acceptable(Predicate<String> atomAcceptable) {
                for (Node part : parts) {
                    if (part.acceptable(atomAcceptable)) {
                        return true;
                    }
                }
                return false;
            }

            @Override
            public void collectAtoms(Collection<String> into) {
                parts.forEach(part -> part.collectAtoms(into));
            }
        }

        /** A conjunction: every part has to be acceptable. */
        private record All(List<Node> parts) implements Node {
            @Override
            public boolean acceptable(Predicate<String> atomAcceptable) {
                for (Node part : parts) {
                    if (!part.acceptable(atomAcceptable)) {
                        return false;
                    }
                }
                return true;
            }

            @Override
            public void collectAtoms(Collection<String> into) {
                parts.forEach(part -> part.collectAtoms(into));
            }
        }

        /** Words and brackets; whitespace separates, brackets are their own tokens. */
        private static List<String> tokenize(String text) {
            List<String> tokens = new ArrayList<>();
            StringBuilder word = new StringBuilder();
            for (char c : (text == null ? "" : text).toCharArray()) {
                if (c == '(' || c == ')') {
                    flush(word, tokens);
                    tokens.add(String.valueOf(c));
                } else if (Character.isWhitespace(c)) {
                    flush(word, tokens);
                } else {
                    word.append(c);
                }
            }
            flush(word, tokens);
            return tokens;
        }

        private static void flush(StringBuilder word, List<String> tokens) {
            if (!word.isEmpty()) {
                tokens.add(word.toString());
                word.setLength(0);
            }
        }

        private static final class Parser {

            private final List<String> tokens;
            private int position;

            private Parser(List<String> tokens) {
                this.tokens = tokens;
            }

            /** {@code expression := term (OR term)*} */
            private Node expression() {
                List<Node> parts = new ArrayList<>();
                parts.add(term());
                while (peekIs("OR")) {
                    position++;
                    parts.add(term());
                }
                return parts.size() == 1 ? parts.get(0) : new Any(List.copyOf(parts));
            }

            /** {@code term := factor (AND factor)*} */
            private Node term() {
                List<Node> parts = new ArrayList<>();
                parts.add(factor());
                while (peekIs("AND")) {
                    position++;
                    parts.add(factor());
                }
                return parts.size() == 1 ? parts.get(0) : new All(List.copyOf(parts));
            }

            /** {@code factor := '(' expression ')' | identifier} */
            private Node factor() {
                if (peekIs("(")) {
                    position++;
                    Node inner = expression();
                    if (!peekIs(")")) {
                        throw new MalformedExpression();
                    }
                    position++;
                    return inner;
                }
                StringBuilder identifier = new StringBuilder();
                while (position < tokens.size() && isIdentifierWord(tokens.get(position))) {
                    if (!identifier.isEmpty()) {
                        identifier.append(' ');
                    }
                    identifier.append(tokens.get(position));
                    position++;
                }
                if (identifier.isEmpty()) {
                    // A dangling operator, an empty bracket pair, or an empty
                    // declaration. Unreadable rather than "matches nothing".
                    throw new MalformedExpression();
                }
                return new Atom(normalize(identifier.toString()));
            }

            private void expectEnd() {
                if (position != tokens.size()) {
                    throw new MalformedExpression();
                }
            }

            private boolean peekIs(String token) {
                return position < tokens.size() && tokens.get(position).equals(token);
            }

            /**
             * Everything that is not a bracket or an operator, {@code WITH}
             * included. SPDX writes its operators in upper case, and honouring
             * that literally is what keeps the {@code and} in a prose license
             * name from being read as a conjunction.
             */
            private static boolean isIdentifierWord(String token) {
                return !token.equals("(")
                        && !token.equals(")")
                        && !token.equals("AND")
                        && !token.equals("OR");
            }
        }

        /** Control flow for an expression this rule does not understand. */
        private static final class MalformedExpression extends RuntimeException {

            private MalformedExpression() {
                super(null, null, false, false);
            }
        }
    }
}
