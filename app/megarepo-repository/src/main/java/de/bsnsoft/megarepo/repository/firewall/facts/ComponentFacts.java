package de.bsnsoft.megarepo.repository.firewall.facts;

import de.bsnsoft.megarepo.core.firewall.FirewallFactsState;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * What the ecosystem declares about one component version: when it was
 * published, and what license it says it has.
 *
 * <p>The input to {@code MIN_AGE} and {@code LICENSE}. Both facts come from
 * package metadata — a POM, a {@code package.json}, a registry document — which
 * means neither can be read on the request thread. They are resolved in the
 * background into {@code firewall_component_facts} and this record is the
 * request path's view of that table.
 *
 * <h2>Three different kinds of "no date"</h2>
 *
 * Keeping them apart is the whole reason this is a record and not a nullable
 * {@code Instant}:
 *
 * <ul>
 *   <li>{@link FirewallFactsState#UNKNOWN}/{@link FirewallFactsState#PENDING} —
 *       nobody has asked yet, or the answer is on its way. A rule must report
 *       {@code INDETERMINATE}; guessing serves a package that may be four
 *       minutes old.</li>
 *   <li>{@link FirewallFactsState#RESOLVED} with a null {@link #publishedAt()} —
 *       the metadata was read and is silent. A settled answer: a MIN_AGE rule
 *       cannot judge this component and must not hold it forever waiting for a
 *       date that is never coming.</li>
 *   <li>{@link FirewallFactsState#UNAVAILABLE} — the ecosystem publishes no such
 *       metadata at all, or resolution has permanently failed. Also settled, and
 *       also not grounds to quarantine.</li>
 * </ul>
 *
 * @param purl qualifier-free purl coordinates this record is about
 * @param state how far resolution has got
 * @param publishedAt upstream publication time of this version, or null
 * @param declaredLicenses declared license identifiers, SPDX where the metadata
 *     gives them and verbatim otherwise; never null, and empty is a fact — it
 *     means the package declares no license
 * @param licenseSource which declaration was read: {@code PACKAGE_METADATA} or
 *     {@code UPSTREAM_REGISTRY}. Never file contents — declared metadata only is
 *     a scope promise, not an implementation shortcut
 * @param source which resolver answered, e.g. {@code maven-pom}
 * @param fetchedAt when it answered
 */
public record ComponentFacts(
        String purl,
        FirewallFactsState state,
        Instant publishedAt,
        List<String> declaredLicenses,
        String licenseSource,
        String source,
        Instant fetchedAt) {

    public ComponentFacts {
        state = state == null ? FirewallFactsState.UNKNOWN : state;
        declaredLicenses = declaredLicenses == null ? List.of() : List.copyOf(declaredLicenses);
    }

    /** Nothing is known and nothing has been asked. */
    public static ComponentFacts unknown(String purl) {
        return new ComponentFacts(purl, FirewallFactsState.UNKNOWN, null, List.of(), null, null, null);
    }

    /** A resolution is in flight. */
    public static ComponentFacts pending(String purl) {
        return new ComponentFacts(purl, FirewallFactsState.PENDING, null, List.of(), null, null, null);
    }

    /**
     * Whether a rule may draw a conclusion from this record.
     *
     * <p>True for {@code RESOLVED} and {@code UNAVAILABLE} alike: a settled "we
     * will never know" is an answer a rule has to live with, not a reason to hold
     * a component indefinitely.
     */
    public boolean isSettled() {
        return state.isSettled();
    }

    /** Whether a rule that needs these facts has to report {@code INDETERMINATE}. */
    public boolean isIndeterminate() {
        return state.isIndeterminate();
    }

    /** How old this version is, when that is knowable. */
    public Optional<Duration> age(Instant now) {
        if (publishedAt == null || now == null || !isSettled()) {
            return Optional.empty();
        }
        return Optional.of(Duration.between(publishedAt, now));
    }

    /** Whether the package states a license at all. Only meaningful once settled. */
    public boolean declaresLicense() {
        return !declaredLicenses.isEmpty();
    }
}
