package de.bsnsoft.megarepo.repository.firewall.facts;

import com.github.packageurl.PackageURL;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves the declared facts about a component version for one ecosystem.
 *
 * <p>One bean per purl type, collected by Spring — the same shape as
 * {@code PurlMapper} and {@code AdvisorySource}, and for the same reason: the
 * knowledge of where a Maven POM lives and where npm keeps its {@code time} map
 * belongs to whoever owns that format, not to a switch statement in the
 * firewall.
 *
 * <h2>Background only</h2>
 *
 * Implementations are called from the facts resolver, never from a request
 * thread, and may block, do I/O and go to the network. All outbound HTTP goes
 * through the configured {@code megarepo.outbound-proxy} settings, like every
 * other outbound call in this codebase.
 *
 * <h2>What may be read</h2>
 *
 * Declared metadata: the artifact's own descriptor (POM, {@code package.json},
 * {@code METADATA}, {@code .nuspec}) as already stored in the blob store, or the
 * upstream registry's metadata document. <b>Not</b> the contents of source files,
 * and not a heuristic over a LICENSE text — no license detection from file
 * contents is a stated scope boundary of this design, not a shortcut.
 *
 * <p>Preferring the locally stored descriptor is the better implementation where
 * one exists: it costs no outbound request, it works in an air-gapped install,
 * and it describes the exact artifact this instance is serving rather than what
 * the registry says today.
 */
public interface ComponentFactsSource {

    /** The purl type this source answers for, e.g. {@code maven}, {@code npm}. */
    String purlType();

    /** Additional purl types handled by the same source, e.g. {@code oci} for a docker source. */
    default Set<String> purlTypeAliases() {
        return Set.of();
    }

    /**
     * Resolves what this ecosystem declares about the given version.
     *
     * @param purl the component, qualifier-free
     * @return the facts, or {@link Optional#empty()} when this ecosystem
     *     genuinely publishes none for the component — which the caller records
     *     as {@code UNAVAILABLE} so nothing retries it forever
     * @throws ComponentFactsException when the attempt failed and is worth
     *     retrying: a timeout, a 5xx, a rate limit. The caller counts the attempt
     *     and leaves the row unresolved; a failing source must never take the
     *     resolver down for the other ecosystems
     */
    Optional<ResolvedFacts> resolve(PackageURL purl) throws ComponentFactsException;

    /**
     * One ecosystem's answer.
     *
     * @param publishedAt when this version was released upstream, or null when
     *     the metadata does not say. Null is a legitimate answer and is stored as
     *     {@code RESOLVED} with no date — a settled "we cannot know", not a
     *     pending one
     * @param declaredLicenses declared license identifiers, SPDX where given;
     *     empty means the package declares none, which is itself what a
     *     deny-by-default license policy is looking for
     * @param licenseSource {@code PACKAGE_METADATA} for a stored descriptor,
     *     {@code UPSTREAM_REGISTRY} for a registry document
     * @param source a short id of the resolver, for the audit trail
     */
    record ResolvedFacts(
            Instant publishedAt,
            List<String> declaredLicenses,
            String licenseSource,
            String source) {

        /** Source constant: read from the artifact's own stored descriptor. */
        public static final String PACKAGE_METADATA = "PACKAGE_METADATA";

        /** Source constant: read from the upstream registry's metadata API. */
        public static final String UPSTREAM_REGISTRY = "UPSTREAM_REGISTRY";

        public ResolvedFacts {
            declaredLicenses = declaredLicenses == null ? List.of() : List.copyOf(declaredLicenses);
        }
    }

    /** A resolution attempt that failed and may be worth repeating. */
    class ComponentFactsException extends Exception {

        public ComponentFactsException(String message) {
            super(message);
        }

        public ComponentFactsException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
