package de.bsnsoft.megarepo.core.firewall;

/**
 * Per-repository enforcement level of the repository firewall.
 *
 * <p>Persisted as the enum name in {@code firewall_repository_config.mode}.
 * Phase 1 implements {@link #OFF} and {@link #AUDIT} only; {@link #QUARANTINE}
 * is already part of the schema so Phase 2 needs no migration.
 */
public enum FirewallMode {

    /** No evaluation at all. */
    OFF,

    /** Evaluate and record violations, but never block a request. */
    AUDIT,

    /** Evaluate and hold offending components. Phase 2. */
    QUARANTINE
}
