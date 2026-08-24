package de.bsnsoft.megarepo.repository.firewall.facts;

import de.bsnsoft.megarepo.repository.firewall.identity.ComponentIdentity;

import java.util.Collection;
import java.util.Map;

/**
 * The request path's door to {@link ComponentFacts}.
 *
 * <h2>The one rule this interface exists to enforce</h2>
 *
 * {@link #lookup} <b>never makes a network call and never blocks on one.</b> It
 * reads {@code firewall_component_facts} and returns whatever is there,
 * including {@link ComponentFacts#unknown}. That is not an optimisation: the
 * customer's constraint is a 20 ms budget for a cache hit and no outbound
 * traffic from a request thread, and an implementation that "just this once"
 * fetches a POM turns every first download of a package into a request waiting
 * on a third-party registry.
 *
 * <p>A miss is therefore answered, not resolved. {@link #requestResolution}
 * enqueues the work and returns immediately; the rule that asked reports
 * {@code INDETERMINATE}, the engine applies the repository's fail mode, and the
 * next request — or the quarantine re-evaluation — sees the resolved row.
 *
 * <p>Implementations must be safe to call from several request threads at once
 * and must not throw: a facts store that is down is a firewall that cannot judge
 * age or license, which is an {@code INDETERMINATE} for those two rules and
 * nothing at all for the rest.
 */
public interface ComponentFactsService {

    /**
     * What is known about this component right now.
     *
     * @param identity the component; only a purl identity can have facts, and
     *     anything else answers {@link ComponentFacts#unknown} with the
     *     identity's key
     * @return never null
     */
    ComponentFacts lookup(ComponentIdentity identity);

    /**
     * The same, for several components in one round trip.
     *
     * @return one entry per requested identity key, never null, never partial
     */
    Map<String, ComponentFacts> lookupAll(Collection<ComponentIdentity> identities);

    /**
     * Queues a background resolution for this component, if one is not already
     * queued or settled.
     *
     * <p>Returns immediately and never throws. Idempotent: calling it on every
     * download of an unresolved component is the expected usage, and must not
     * produce one queue entry per request.
     */
    void requestResolution(ComponentIdentity identity);
}
