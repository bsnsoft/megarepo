package de.bsnsoft.megarepo.repository.firewall.rule.impl;

import com.github.packageurl.MalformedPackageURLException;
import com.github.packageurl.PackageURL;
import de.bsnsoft.megarepo.core.firewall.FirewallFactsState;
import de.bsnsoft.megarepo.core.firewall.FirewallFailMode;
import de.bsnsoft.megarepo.core.firewall.FirewallMode;
import de.bsnsoft.megarepo.core.repository.RepositoryType;
import de.bsnsoft.megarepo.repository.advisory.AdvisoryFinding;
import de.bsnsoft.megarepo.repository.advisory.AdvisoryMatch;
import de.bsnsoft.megarepo.repository.advisory.MatchConfidence;
import de.bsnsoft.megarepo.repository.firewall.FirewallRepositorySettings;
import de.bsnsoft.megarepo.repository.firewall.facts.ComponentFacts;
import de.bsnsoft.megarepo.repository.firewall.facts.ComponentFactsService;
import de.bsnsoft.megarepo.repository.firewall.identity.ComponentIdentity;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRuleContext;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Contexts, identities and facts for the rule unit tests.
 *
 * <p>Every rule in this package is a pure function of a
 * {@link FirewallRuleContext} and its {@code FirewallRuleSettings}, so its tests
 * need no Spring and no database — only a readable way to say "a proxied maven
 * component, published two hours ago, that no advisory names".
 */
final class RuleContexts {

    /** The clock every test agrees on. */
    static final Instant NOW = Instant.parse("2026-08-24T12:00:00Z");

    static final UUID REPOSITORY_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private RuleContexts() {}

    /** {@code pkg:maven/com.acme/util@1.0.0}. */
    static ComponentIdentity.Purl maven() {
        return maven("com.acme", "util", "1.0.0");
    }

    static ComponentIdentity.Purl maven(String namespace, String name, String version) {
        try {
            return new ComponentIdentity.Purl(
                    new PackageURL("maven", namespace, name, version, null, null));
        } catch (MalformedPackageURLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A raw file that has a digest — the identity a raw repository actually produces. */
    static ComponentIdentity.Hash hash() {
        return ComponentIdentity.Hash.sha256("e3b0c44298fc1c149afbf4c8996fb924");
    }

    /** A component with neither coordinates nor a digest. */
    static ComponentIdentity.Unidentified unidentified(String format) {
        return new ComponentIdentity.Unidentified(format, null, "blob", "1");
    }

    /** Facts that have been resolved: a publication date and declared licenses. */
    static ComponentFacts resolved(String key, Instant publishedAt, String... licenses) {
        return new ComponentFacts(
                key,
                FirewallFactsState.RESOLVED,
                publishedAt,
                List.of(licenses),
                "PACKAGE_METADATA",
                "maven-pom",
                NOW);
    }

    /** Facts that will never resolve — the ecosystem publishes none. */
    static ComponentFacts unavailable(String key) {
        return new ComponentFacts(
                key, FirewallFactsState.UNAVAILABLE, null, List.of(), null, "maven-pom", NOW);
    }

    /** An advisory finding at the given confidence. */
    static AdvisoryFinding finding(String advisoryId, MatchConfidence confidence) {
        return new AdvisoryFinding(
                advisoryId,
                "summary",
                "HIGH",
                7.5,
                null,
                NOW,
                NOW,
                List.of(new AdvisoryMatch(advisoryId, "osv", confidence, "[1.0.0,2.0.0)")));
    }

    /**
     * An {@link ObjectProvider} that hands out the given service, or nothing when
     * it is null — the state of a build without the facts work package.
     */
    @SuppressWarnings("unchecked")
    static ObjectProvider<ComponentFactsService> provider(ComponentFactsService service) {
        ObjectProvider<ComponentFactsService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(service);
        return provider;
    }

    /** A proxied download — the shape most rules care about. */
    static Builder proxied() {
        return new Builder().repositoryType(RepositoryType.PROXY);
    }

    /** A download from a hosted repository, i.e. of something published here. */
    static Builder hosted() {
        return new Builder().repositoryType(RepositoryType.HOSTED);
    }

    static final class Builder {

        private ComponentIdentity identity = maven();
        private RepositoryType repositoryType = RepositoryType.PROXY;
        private List<AdvisoryFinding> findings = List.of();
        private ComponentFacts facts;
        private boolean upload;
        private boolean preExisting;
        private Instant evaluatedAt = NOW;

        Builder identity(ComponentIdentity identity) {
            this.identity = identity;
            return this;
        }

        Builder repositoryType(RepositoryType repositoryType) {
            this.repositoryType = repositoryType;
            return this;
        }

        Builder findings(AdvisoryFinding... findings) {
            this.findings = List.of(findings);
            return this;
        }

        Builder facts(ComponentFacts facts) {
            this.facts = facts;
            return this;
        }

        /** Resolved facts for this context's own component. */
        Builder publishedAt(Instant publishedAt) {
            this.facts = resolved(identity.key(), publishedAt);
            return this;
        }

        /** Resolved facts declaring these licenses and no publication date. */
        Builder declares(String... licenses) {
            this.facts = resolved(identity.key(), null, licenses);
            return this;
        }

        Builder upload() {
            this.upload = true;
            return this;
        }

        Builder preExisting() {
            this.preExisting = true;
            return this;
        }

        Builder at(Instant evaluatedAt) {
            this.evaluatedAt = evaluatedAt;
            return this;
        }

        FirewallRuleContext build() {
            return new FirewallRuleContext(
                    REPOSITORY_ID,
                    "maven-central-proxy",
                    repositoryType,
                    "com/acme/util/1.0.0/util-1.0.0.jar",
                    identity,
                    findings,
                    facts,
                    new FirewallRepositorySettings(
                            FirewallMode.QUARANTINE, FirewallFailMode.FAIL_CLOSED, null, true),
                    upload,
                    preExisting,
                    evaluatedAt);
        }
    }
}
