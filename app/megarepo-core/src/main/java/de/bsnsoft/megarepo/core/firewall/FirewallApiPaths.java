package de.bsnsoft.megarepo.core.firewall;

/**
 * The firewall's REST paths, as compile-time constants both sides can reach.
 *
 * <h2>Why this is in core and not next to the controller</h2>
 *
 * {@code FirewallExemptionController} declares the exemptions path and is the
 * obvious home for it, but the 403 body that has to <em>link</em> to that path is
 * built in {@code megarepo-repository}, and {@code megarepo-rest-api} depends on
 * {@code megarepo-repository} rather than the other way round. So the constant
 * lives in the one module both can see, and the controller's own
 * {@code BASE_PATH} is defined from it.
 *
 * <p>The alternative — writing {@code "/api/v1/firewall/exemptions"} a second
 * time in the block response — is exactly the drift that produces a 403 telling a
 * developer to POST to an endpoint that was renamed a release ago, discovered by
 * the developer and not by a test.
 */
public final class FirewallApiPaths {

    /**
     * Where exemptions are requested and managed.
     *
     * <p>A request is {@code POST} here with the component key, the rule type and
     * the repository that resolved the artifact — the three things the block
     * response already knows and the requester would otherwise have to copy out
     * of a build log by hand.
     */
    public static final String EXEMPTIONS = "/api/v1/firewall/exemptions";

    private FirewallApiPaths() {}
}
