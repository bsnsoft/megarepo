package de.bsnsoft.megarepo.core.firewall;

/**
 * How far the firewall has got in learning the declared facts about one
 * component version — its publication date and its declared licenses.
 *
 * <p>Persisted as the enum name in {@code firewall_component_facts.state}, with a
 * CHECK constraint.
 *
 * <h2>Why facts are a cached table and not a lookup</h2>
 *
 * {@link FirewallRuleType#MIN_AGE} needs to know when a version was published,
 * and {@link FirewallRuleType#LICENSE} needs to know what license it declares.
 * Neither fact is in MegaRepo's own tables: {@code components} carries
 * coordinates and a local {@code created_at}, which is when <em>this instance
 * first saw</em> the artifact, not when the ecosystem released it. A proxy that
 * pulls a three-year-old library today would read it as three seconds old and
 * quarantine it.
 *
 * <p>The real facts come from package metadata — the POM, the {@code
 * package.json}, the registry's own API. Reading any of those on the request
 * thread is exactly what the customer forbade, so they are resolved in the
 * background and read from this table on the request path. That turns "we do not
 * know yet" into a state a rule has to handle, which is what the constants below
 * are for.
 *
 * @see FirewallQuarantineReason#EVALUATION_INCOMPLETE
 */
public enum FirewallFactsState {

    /**
     * Never asked for. No row exists, or one was created as a placeholder and no
     * resolution has been attempted.
     */
    UNKNOWN,

    /** A background resolution has been queued and has not finished. */
    PENDING,

    /**
     * Resolved. {@code published_at} and {@code declared_licenses} carry whatever
     * the source actually stated — either may still be null/empty if the package
     * metadata itself is silent, which is a fact in its own right and not a
     * failure.
     */
    RESOLVED,

    /**
     * Resolution was attempted and cannot succeed: the ecosystem publishes no
     * such metadata, the artifact is not a package (raw, Docker layer), or every
     * attempt failed. Held so the resolver does not retry it on every download.
     */
    UNAVAILABLE;

    /** Whether a rule may read the fact columns and treat them as final. */
    public boolean isSettled() {
        return this == RESOLVED || this == UNAVAILABLE;
    }

    /**
     * Whether a rule that needs these facts must report
     * {@code INDETERMINATE} rather than a verdict.
     *
     * <p>{@link #UNAVAILABLE} is not indeterminate: "this ecosystem does not
     * publish publication dates" is a settled answer, and a MIN_AGE rule that
     * quarantined every raw file forever because of it would be a bug, not
     * caution.
     */
    public boolean isIndeterminate() {
        return this == UNKNOWN || this == PENDING;
    }
}
