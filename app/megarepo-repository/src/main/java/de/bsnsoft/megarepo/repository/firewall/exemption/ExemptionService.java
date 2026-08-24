package de.bsnsoft.megarepo.repository.firewall.exemption;

import de.bsnsoft.megarepo.core.firewall.FirewallRuleType;
import de.bsnsoft.megarepo.repository.firewall.identity.ComponentIdentity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The exemption workflow: request, approve, expire.
 *
 * <p>Everything the V8 whitelist did not have. An exemption states what it
 * covers, who asked, who signed it, why, and when it stops — and it does stop:
 * an expired exemption blocks again, which is the single behaviour that
 * distinguishes this from a list of coordinates nobody dares delete.
 *
 * <h2>The request-path method is {@link #findApplicable}, and it is a local read</h2>
 *
 * One indexed query against a partial index over approved rows. No network, no
 * scan, and it must not throw: an exemption store that is unreachable is a
 * firewall that does not know about exemptions, which denies a download somebody
 * had permission for — bad, but recoverable — whereas an exception there takes
 * the whole evaluation with it.
 *
 * <h2>Legacy keys</h2>
 *
 * Rows migrated from the V8 whitelist carry a
 * {@code format:namespace:name[:version]} coordinate rather than a purl, because
 * no SQL migration can convert one into the other (see V18). {@link #findApplicable}
 * is where that is absorbed: it builds every key form the component can be named
 * by — the purl key, its version-less form, and, while any legacy rows exist, the
 * V8 coordinate and its version-less prefix — and matches all of them in one
 * query. Reproducing the V8 comparison exactly is the requirement; an operator's
 * existing whitelist entry must go on working the day after the upgrade.
 */
public interface ExemptionService {

    /**
     * The live exemptions that cover this component in this repository.
     *
     * <p>Returns every applicable exemption rather than the first, because the
     * caller has to record <em>which</em> one let a violation through, and
     * because a component may well be covered by a narrow rule-scoped exemption
     * and a broad one at the same time.
     *
     * @param repositoryId the repository the component was resolved from
     * @param identity the component
     * @param at the clock; an exemption whose expiry has passed is not returned
     *     even if the daily expiry sweep has not flipped its state yet
     */
    List<FirewallExemption> findApplicable(UUID repositoryId, ComponentIdentity identity, Instant at);

    /**
     * The first live exemption covering this component <em>for this rule</em>.
     *
     * <p>The form the policy engine uses: a rule-scoped exemption suppresses the
     * rule it names and nothing else, which is what makes "exempt from MIN_AGE
     * but not from KNOWN_MALICIOUS" expressible instead of forcing an operator
     * into a blanket pass.
     */
    Optional<FirewallExemption> findApplicable(
            UUID repositoryId, ComponentIdentity identity, FirewallRuleType ruleType, Instant at);

    /** Records a request. Creates it {@code REQUESTED}; it changes no download yet. */
    FirewallExemption request(ExemptionRequest request);

    /**
     * Approves a request, making it effective.
     *
     * @param expiresAt when it lapses. Null means never, and is a decision the
     *     approver takes deliberately — the API does not default to it
     * @throws IllegalStateException when the exemption is not in a state that can
     *     be approved
     */
    FirewallExemption approve(UUID id, String approver, String note, Instant expiresAt);

    /** Refuses a request. Kept, so the next requester can see it was asked before. */
    FirewallExemption reject(UUID id, String approver, String note);

    /**
     * Withdraws an approved exemption before it expires.
     *
     * <p>Distinct from deleting it and from backdating its expiry: the first
     * destroys the record of a decision that was live in production, the second
     * makes the log claim it lapsed on its own.
     */
    FirewallExemption revoke(UUID id, String approver, String note);

    /** One exemption. */
    Optional<FirewallExemption> find(UUID id);

    /** The management list. */
    Page<FirewallExemption> list(ExemptionQuery query, Pageable pageable);

    /** Counts per state, for the admin overview and the approval-queue badge. */
    ExemptionSummary summary();

    /**
     * Flips approved exemptions whose expiry has passed to {@code EXPIRED}.
     *
     * <p>Driven by the daily task (V19). A stored transition rather than a
     * derived one so that the exemption list, the violation log and the operator
     * all agree about when it stopped applying.
     *
     * @return how many lapsed
     */
    int expireLapsed(Instant now);

    /**
     * Announces exemptions that lapse within the notice window, once each.
     *
     * <p>The customer asked to be told before an exemption expires, and the
     * reason is operational rather than polite: an exemption lapsing unannounced
     * shows up as a build that broke overnight for no visible change.
     *
     * @param lead how far ahead to look
     * @return the exemptions announced
     */
    List<FirewallExemption> notifyUpcomingExpiry(Instant now, Duration lead);

    /** Counts per state. */
    record ExemptionSummary(
            long requested, long approved, long rejected, long expired, long revoked, long legacy) {}
}
