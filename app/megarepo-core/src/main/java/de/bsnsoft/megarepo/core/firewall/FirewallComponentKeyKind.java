package de.bsnsoft.megarepo.core.firewall;

/**
 * Which naming scheme a stored component key uses.
 *
 * <p>Persisted as the enum name in {@code firewall_exemption.key_kind}, with a
 * CHECK constraint.
 *
 * <p>Exists for exactly one reason: the V8 whitelist rows that Phase 2 migrates
 * are not purls and cannot be turned into purls by a SQL migration. A V8 entry
 * reads {@code maven2:org.apache.logging.log4j:log4j-core:2.14.1} —
 * {@code format:namespace:name:version}, with the repository's raw format key
 * and no per-ecosystem name normalisation. Producing the matching purl needs
 * {@code PurlMapper}, which lives in the format modules and is not available to
 * Flyway.
 *
 * <p>The alternatives were worse. Guessing the purl in SQL would silently widen
 * or narrow an operator's existing exemption — a whitelist entry that stops
 * matching is a build that breaks on upgrade, and one that starts matching more
 * is a hole. Dropping the rows would break the customer's requirement outright.
 * So the legacy key is stored verbatim, tagged, and matched by its own rules,
 * which reproduce the V8 matcher exactly.
 *
 * <p>New exemptions are always {@link #PURL}. {@link #LEGACY_COORDINATE} is
 * write-once, by the migration, and read by the exemption matcher until the last
 * such row is gone.
 */
public enum FirewallComponentKeyKind {

    /**
     * {@code ComponentIdentity.key()} — a canonical purl
     * ({@code pkg:maven/org.apache.logging.log4j/log4j-core@2.14.1}) or, for
     * formats without coordinates, a content digest ({@code sha256:…}).
     */
    PURL,

    /**
     * A V8 {@code nvd_firewall_whitelist} value:
     * {@code format:namespace:name:version} for a single version, or
     * {@code format:namespace:name} for every version — which is how the V8
     * matcher's prefix rule behaved.
     */
    LEGACY_COORDINATE;

    /** Whether keys of this kind are still produced by new code. */
    public boolean isCurrent() {
        return this == PURL;
    }
}
