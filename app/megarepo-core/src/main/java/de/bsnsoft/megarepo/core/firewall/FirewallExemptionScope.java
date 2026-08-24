package de.bsnsoft.megarepo.core.firewall;

/**
 * How wide an exemption reaches over versions.
 *
 * <p>Persisted as the enum name in {@code firewall_exemption.scope_type}, with a
 * CHECK constraint — this is a closed set the customer specified.
 *
 * <p><b>The word is "exemption".</b> Not "waiver", not "whitelist". The customer
 * settled that in item 7 of the request and it holds for the entity, the API
 * path, the UI label and every identifier in this codebase.
 *
 * <p>The repository half of the scope is not in this enum: it is the nullable
 * {@code firewall_exemption.repository_id} column, where NULL means "every
 * repository". Two independent dimensions, two independent columns — folding
 * them into four constants would make "this version, everywhere" and "all
 * versions, here" look like unrelated things instead of the same two switches.
 */
public enum FirewallExemptionScope {

    /**
     * Exactly the component version named by the exemption's key.
     *
     * <p>The safe default, and the one the block page offers a developer: an
     * exemption granted because {@code left-pad@1.3.0} is fine says nothing
     * about {@code left-pad@1.3.1}, which does not exist yet and may be the
     * compromised one.
     */
    VERSION,

    /**
     * Every version of the component, including versions published after the
     * exemption was approved.
     *
     * <p>Deliberately the wider, more dangerous option. It is what the V8
     * whitelist did implicitly — an entry for one version quietly covered all of
     * them — and turning that into an explicit, expiring, approver-signed choice
     * is one of the reasons this table exists.
     */
    COMPONENT;

    /** Whether the exemption ignores the version part of the component key. */
    public boolean ignoresVersion() {
        return this == COMPONENT;
    }
}
