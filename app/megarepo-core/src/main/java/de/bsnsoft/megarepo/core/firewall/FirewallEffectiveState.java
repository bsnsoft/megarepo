package de.bsnsoft.megarepo.core.firewall;

/**
 * What the firewall <em>actually does</em> for one repository, once the global
 * enforcement switch and the repository's configured {@link FirewallMode} are
 * taken together.
 *
 * <p>{@link FirewallMode} alone cannot answer that question, and reading it as
 * if it could is the dangerous mistake: a repository configured
 * {@link FirewallMode#QUARANTINE} on an instance whose enforcement switch is off
 * blocks nothing. An operator who sees only the word "Quarantine" concludes they
 * are protected. They are not — they are collecting evidence.
 *
 * <p>The combination is resolved here, once, and every surface that shows a
 * state (REST, Web UI, and any future report) shows the result of
 * {@link #resolve} rather than re-deriving it. Duplicating the rule is how the
 * API and the UI end up disagreeing about whether an instance is armed.
 */
public enum FirewallEffectiveState {

    /** {@link FirewallMode#OFF} — the repository is not looked at at all. */
    NOT_EVALUATED,

    /**
     * {@link FirewallMode#AUDIT} — downloads are evaluated and violations
     * recorded, and every request is served. Independent of the global switch:
     * AUDIT never blocks, armed or not.
     */
    OBSERVING,

    /**
     * {@link FirewallMode#QUARANTINE} configured, global enforcement off.
     *
     * <p>Behaves exactly like {@link #OBSERVING} — nothing is held back — but is
     * a distinct state on purpose, because the configuration says otherwise. It
     * is the one combination that can be mistaken for protection, so it is the
     * one that must be named.
     */
    QUARANTINE_NOT_ENFORCED,

    /**
     * {@link FirewallMode#QUARANTINE} configured and global enforcement on:
     * matching downloads are refused. The only state in which a build can break.
     */
    BLOCKING;

    /**
     * Resolve the state from the two facts that produce it.
     *
     * @param enforcementEnabled the global switch
     *     ({@code firewall_enforcement_settings.enabled})
     * @param mode the repository's configured mode; null is read as
     *     {@link FirewallMode#OFF}, matching a repository with no
     *     {@code firewall_repository_config} row
     */
    public static FirewallEffectiveState resolve(boolean enforcementEnabled, FirewallMode mode) {
        if (mode == null || mode == FirewallMode.OFF) {
            return NOT_EVALUATED;
        }
        if (mode == FirewallMode.AUDIT) {
            return OBSERVING;
        }
        return enforcementEnabled ? BLOCKING : QUARANTINE_NOT_ENFORCED;
    }

    /** Whether a download can actually be refused in this state. */
    public boolean blocks() {
        return this == BLOCKING;
    }
}
