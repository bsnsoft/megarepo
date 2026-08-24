package de.bsnsoft.megarepo.repository.firewall.quarantine;

import de.bsnsoft.megarepo.core.firewall.FirewallQuarantineReason;
import de.bsnsoft.megarepo.core.firewall.FirewallQuarantineResolution;
import de.bsnsoft.megarepo.core.firewall.FirewallQuarantineState;
import de.bsnsoft.megarepo.database.entity.FirewallQuarantineEntity;
import de.bsnsoft.megarepo.database.repository.FirewallQuarantineJpaRepository;
import de.bsnsoft.megarepo.repository.firewall.FirewallEvaluation;
import de.bsnsoft.megarepo.repository.firewall.FirewallRequestContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The quarantine state machine, in one file.
 *
 * <h2>Everything that can change a row's state is here</h2>
 *
 * {@link QuarantineReevaluator} decides what <em>should</em> happen to a held
 * component; nothing but this class writes it down. A state machine whose
 * transitions can be performed from several places is a state machine that
 * eventually performs one nobody designed — and this one guards the customer's
 * hardest constraint, so it is worth the indirection.
 *
 * <h2>The two ways nothing happens</h2>
 *
 * <ul>
 *   <li><b>Quarantine is switched off.</b>
 *       {@code megarepo.firewall.quarantine.enabled=false} makes {@link #quarantine}
 *       record nothing and {@link #find} find nothing, so an enforcing repository
 *       decides on the policy alone with no queue in between. Existing rows are
 *       left exactly as they are: switching a mechanism off is a change of
 *       behaviour, not a data migration, and an operator who switches it back on
 *       expects their queue back.</li>
 *   <li><b>The component was already there.</b> An artifact whose asset predates
 *       the moment enforcement was switched on is never held. The check lives
 *       here rather than in the caller because it holds in one place or it holds
 *       nowhere — the customer's rule is that switching enforcement on may not
 *       break a build that worked yesterday.</li>
 * </ul>
 *
 * <h2>Transitions</h2>
 *
 * <pre>
 *   QUARANTINED --release--&gt; RELEASED        QUARANTINED --block--&gt; BLOCKED
 *   BLOCKED     --release--&gt; RELEASED *      RELEASED    --block--&gt; BLOCKED *
 * </pre>
 *
 * <p>The two starred transitions are a <em>re-decision</em> of an entry that was
 * already decided, and they are allowed only for a deliberate one: an operator,
 * an approved exemption, or a policy edit. The scheduled sweep can never perform
 * them — it only ever looks at entries that are still held — and an automatic
 * resolution arriving on a decided row means something re-ran a decision it
 * should not have, which throws rather than being written.
 *
 * <p>Nothing returns to {@code QUARANTINED}. "Held again" with no fresh trigger
 * is indistinguishable from a stuck queue, and a fresh trigger goes through
 * {@link #quarantine} and produces its own reason and snapshot.
 */
@Service
public class DefaultQuarantineService implements QuarantineService {

    private static final Logger log = LoggerFactory.getLogger(DefaultQuarantineService.class);

    /** States a release is a legal transition from. */
    private static final Set<FirewallQuarantineState> RELEASABLE_FROM =
            Set.of(FirewallQuarantineState.QUARANTINED, FirewallQuarantineState.BLOCKED);

    /** States a block is a legal transition from. */
    private static final Set<FirewallQuarantineState> BLOCKABLE_FROM =
            Set.of(FirewallQuarantineState.QUARANTINED, FirewallQuarantineState.RELEASED);

    /**
     * Resolutions that may re-decide an already-decided entry.
     *
     * <p>All three are somebody's deliberate act — an operator in the queue, an
     * approved exemption, an edited policy. {@code AGE_REACHED} and its siblings
     * are the sweep's own answers, and the sweep only ever looks at held
     * entries.
     */
    private static final Set<FirewallQuarantineResolution> DELIBERATE = Set.of(
            FirewallQuarantineResolution.MANUAL_RELEASE,
            FirewallQuarantineResolution.MANUAL_BLOCK,
            FirewallQuarantineResolution.EXEMPTION_GRANTED,
            FirewallQuarantineResolution.POLICY_CHANGED);

    private final FirewallQuarantineJpaRepository entries;
    private final QuarantineMapper mapper;
    private final QuarantineReevaluator reevaluator;
    private final QuarantineProperties properties;

    @PersistenceContext
    private EntityManager entityManager;

    public DefaultQuarantineService(
            FirewallQuarantineJpaRepository entries,
            QuarantineMapper mapper,
            QuarantineReevaluator reevaluator,
            QuarantineProperties properties) {
        this.entries = entries;
        this.mapper = mapper;
        this.reevaluator = reevaluator;
        this.properties = properties;
    }

    // ------------------------------------------------------------------
    // Request path
    // ------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public Optional<FirewallQuarantineEntry> find(UUID repositoryId, String componentKey) {
        if (!properties.enabled() || repositoryId == null || isBlank(componentKey)) {
            return Optional.empty();
        }
        try {
            return entries.findByRepositoryIdAndComponentKey(repositoryId, componentKey)
                    .map(mapper::toEntry);
        } catch (RuntimeException e) {
            // A quarantine store that cannot be read is a firewall that has not
            // seen this component before. Failing the download instead would let
            // a database hiccup do what no policy asked for.
            log.warn("Could not read the quarantine entry for {} in {}", componentKey, repositoryId, e);
            return Optional.empty();
        }
    }

    @Override
    @Transactional
    public void recordHit(UUID quarantineId, Instant seenAt) {
        if (!properties.enabled() || quarantineId == null) {
            return;
        }
        try {
            entries.recordHit(quarantineId, seenAt == null ? Instant.now() : seenAt);
        } catch (RuntimeException e) {
            // The verdict has already been given. A counter that did not
            // increment is a worse log line, not a different answer.
            log.debug("Could not record a quarantine hit for {}", quarantineId, e);
        }
    }

    @Override
    @Transactional
    public Optional<FirewallQuarantineEntry> quarantine(
            FirewallEvaluation evaluation,
            FirewallQuarantineReason reason,
            FirewallRequestContext context) {

        if (!properties.enabled()) {
            return Optional.empty();
        }
        if (evaluation == null || reason == null || !reason.isEntryReason()) {
            // POLICY_VIOLATION is a reason an entry *leaves* under, never one it
            // arrives under: a policy violation found on the request path is
            // refused outright and produces no queue entry at all.
            return Optional.empty();
        }
        if (evaluation.preExisting()) {
            // The customer's hardest constraint, enforced at the only place that
            // can create an entry.
            return Optional.empty();
        }
        String componentKey = evaluation.componentKey();
        if (isBlank(componentKey) || evaluation.repositoryId() == null) {
            // Nothing to key an entry on. A queue row that names no component is
            // a row an operator cannot act on.
            return Optional.empty();
        }

        Instant now = Instant.now();
        FirewallQuarantineEntity entity = entries
                .findByRepositoryIdAndComponentKey(evaluation.repositoryId(), componentKey)
                .orElse(null);

        if (entity == null) {
            entity = new FirewallQuarantineEntity();
            entity.setRepositoryId(evaluation.repositoryId());
            entity.setRepositoryName(evaluation.repositoryName());
            entity.setComponentKey(componentKey);
            entity.setFirstSeen(now);
            entity.setHitCount(1);
            entity.setCreatedAt(now);
        } else if (entity.getState() != FirewallQuarantineState.QUARANTINED) {
            // Already decided. A held-then-released component that is requested
            // again is served on that decision, and re-holding it here would
            // undo an operator's release on the next download.
            //
            // Empty rather than the existing entry, because the contract says
            // empty means "nothing was held" and a caller that refuses whatever
            // this returns would otherwise refuse a component somebody released.
            entity.setLastSeen(now);
            entity.setHitCount(entity.getHitCount() + 1);
            entity.setUpdatedAt(now);
            entries.save(entity);
            return Optional.empty();
        } else {
            entity.setHitCount(entity.getHitCount() + 1);
        }

        entity.setPath(evaluation.path());
        entity.setState(FirewallQuarantineState.QUARANTINED);
        entity.setReasonCode(reason);
        entity.setPolicyId(evaluation.decision() == null ? null : evaluation.decision().policyId());
        entity.setEvaluation(mapper.snapshot(evaluation, context));
        entity.setLastSeen(now);
        entity.setUpdatedAt(now);
        if (entity.getNextEvaluationAt() == null) {
            // Due at once. The first sweep after the hold is the one most likely
            // to release it — the facts it was missing are usually resolved by
            // then — and a fresh entry has no backoff history to extrapolate.
            entity.setNextEvaluationAt(now);
        }

        FirewallQuarantineEntity saved = entries.save(entity);
        log.info("Quarantined {} in {} ({})", componentKey, evaluation.repositoryName(), reason);
        return Optional.of(mapper.toEntry(saved));
    }

    // ------------------------------------------------------------------
    // Operator decisions
    // ------------------------------------------------------------------

    @Override
    @Transactional
    public FirewallQuarantineEntry release(UUID quarantineId, QuarantineDecision decision) {
        return decide(quarantineId, decision, FirewallQuarantineState.RELEASED);
    }

    @Override
    @Transactional
    public FirewallQuarantineEntry block(UUID quarantineId, QuarantineDecision decision) {
        return decide(quarantineId, decision, FirewallQuarantineState.BLOCKED);
    }

    private FirewallQuarantineEntry decide(
            UUID quarantineId, QuarantineDecision decision, FirewallQuarantineState target) {

        if (decision == null) {
            throw new IllegalArgumentException("a quarantine decision must be recorded");
        }
        boolean releasing = target == FirewallQuarantineState.RELEASED;
        if (decision.resolution().releases() != releasing) {
            // Otherwise a row could end up saying state=RELEASED,
            // resolution=MANUAL_BLOCK, which is a queue nobody can read.
            throw new IllegalArgumentException(
                    "resolution %s cannot %s a quarantine entry"
                            .formatted(decision.resolution(), releasing ? "release" : "block"));
        }

        FirewallQuarantineEntity entity = entries.findById(quarantineId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Quarantine entry not found: " + quarantineId));

        Set<FirewallQuarantineState> legal = releasing ? RELEASABLE_FROM : BLOCKABLE_FROM;
        if (!legal.contains(entity.getState())) {
            throw new IllegalStateException(
                    "Quarantine entry %s is %s and cannot be %s"
                            .formatted(quarantineId, entity.getState(),
                                    releasing ? "released" : "blocked"));
        }
        if (entity.getState() != FirewallQuarantineState.QUARANTINED
                && !DELIBERATE.contains(decision.resolution())) {
            throw new IllegalStateException(
                    "Quarantine entry %s was already decided (%s); %s is not a deliberate re-decision"
                            .formatted(quarantineId, entity.getState(), decision.resolution()));
        }

        return mapper.toEntry(entries.save(applyDecision(entity, decision, target, Instant.now())));
    }

    /** Writes one decided transition. The single place a terminal state is set. */
    private static FirewallQuarantineEntity applyDecision(
            FirewallQuarantineEntity entity,
            QuarantineDecision decision,
            FirewallQuarantineState target,
            Instant now) {

        entity.setState(target);
        entity.setResolution(decision.resolution());
        entity.setDecidedAt(now);
        entity.setDecidedBy(decision.decidedBy());
        entity.setDecisionReason(trim(decision.note(), 1000));
        entity.setExemptionId(decision.exemptionId());
        entity.setLastEvaluatedAt(now);
        // A decided entry is not looked at again by the sweep, and leaving a due
        // date on it would make findDueForReevaluation's index carry rows that
        // can never come back.
        entity.setNextEvaluationAt(null);
        entity.setUpdatedAt(now);
        if (target == FirewallQuarantineState.BLOCKED
                && decision.resolution() == FirewallQuarantineResolution.POLICY_VIOLATION) {
            entity.setReasonCode(FirewallQuarantineReason.POLICY_VIOLATION);
        }
        return entity;
    }

    // ------------------------------------------------------------------
    // Queue and overview
    // ------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public Page<FirewallQuarantineEntry> queue(QuarantineQuery query, Pageable pageable) {
        QuarantineQuery filters = query == null ? QuarantineQuery.all() : query;
        Pageable page = pageable == null ? PageRequest.of(0, 50) : pageable;

        if (entityManager == null) {
            // No persistence context — a unit test constructed this service
            // directly. Fall back to the derived queries, which cover the two
            // filters the request path uses.
            return fallbackQueue(filters, page);
        }

        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        Map<String, Object> parameters = new LinkedHashMap<>();
        if (filters.state() != null) {
            where.append(" AND q.state = :state");
            parameters.put("state", filters.state());
        }
        if (filters.repositoryId() != null) {
            where.append(" AND q.repositoryId = :repositoryId");
            parameters.put("repositoryId", filters.repositoryId());
        }
        if (filters.reason() != null) {
            where.append(" AND q.reasonCode = :reason");
            parameters.put("reason", filters.reason());
        }
        if (!isBlank(filters.componentKeyContains())) {
            where.append(" AND LOWER(q.componentKey) LIKE :search");
            parameters.put("search",
                    "%" + filters.componentKeyContains().trim().toLowerCase(Locale.ROOT) + "%");
        }

        TypedQuery<Long> count = entityManager.createQuery(
                "SELECT COUNT(q) FROM FirewallQuarantineEntity q" + where, Long.class);
        TypedQuery<FirewallQuarantineEntity> rows = entityManager.createQuery(
                "SELECT q FROM FirewallQuarantineEntity q" + where + " ORDER BY q.firstSeen DESC",
                FirewallQuarantineEntity.class);
        parameters.forEach((name, value) -> {
            count.setParameter(name, value);
            rows.setParameter(name, value);
        });

        long total = count.getSingleResult();
        rows.setFirstResult((int) page.getOffset());
        rows.setMaxResults(page.getPageSize());

        List<FirewallQuarantineEntry> content = new ArrayList<>();
        for (FirewallQuarantineEntity entity : rows.getResultList()) {
            content.add(mapper.toEntry(entity));
        }
        return new PageImpl<>(content, page, total);
    }

    private Page<FirewallQuarantineEntry> fallbackQueue(QuarantineQuery filters, Pageable page) {
        Page<FirewallQuarantineEntity> rows;
        if (filters.state() != null && filters.repositoryId() != null) {
            rows = entries.findByRepositoryIdAndStateOrderByFirstSeenDesc(
                    filters.repositoryId(), filters.state(), page);
        } else if (filters.state() != null) {
            rows = entries.findByStateOrderByFirstSeenDesc(filters.state(), page);
        } else {
            rows = entries.findAllByOrderByFirstSeenDesc(page);
        }
        return rows.map(mapper::toEntry);
    }

    @Override
    @Transactional(readOnly = true)
    public QuarantineSummary summary() {
        return new QuarantineSummary(
                entries.countByState(FirewallQuarantineState.QUARANTINED),
                entries.countByState(FirewallQuarantineState.RELEASED),
                entries.countByState(FirewallQuarantineState.BLOCKED));
    }

    // ------------------------------------------------------------------
    // Automatic release
    // ------------------------------------------------------------------

    /**
     * {@inheritDoc}
     *
     * <p>Each entry is applied on its own. One row whose repository has been
     * deleted, or whose component key no longer parses, must not roll back the
     * ninety-nine releases the same sweep earned — the whole point of the sweep
     * is that a developer's build starts working again without anybody being
     * asked.
     */
    @Override
    public int reevaluateDue(Instant now, int limit) {
        if (!properties.enabled()) {
            return 0;
        }
        Instant at = now == null ? Instant.now() : now;
        int batch = Math.clamp(limit <= 0 ? properties.reevaluationBatchSize() : limit,
                1, properties.reevaluationBatchSize());

        List<FirewallQuarantineEntity> due;
        try {
            due = entries.findDueForReevaluation(at, PageRequest.of(0, batch));
        } catch (RuntimeException e) {
            log.warn("Could not read the quarantine re-evaluation work list", e);
            return 0;
        }

        int changed = 0;
        for (FirewallQuarantineEntity entity : due) {
            try {
                if (apply(entity, reevaluator.reevaluate(entity, at), at)) {
                    changed++;
                }
            } catch (RuntimeException e) {
                log.warn("Could not apply the re-evaluation of {} in {}",
                        entity.getComponentKey(), entity.getRepositoryName(), e);
            }
        }
        if (changed > 0) {
            log.info("Quarantine re-evaluation: {} of {} due entries changed state", changed, due.size());
        }
        return changed;
    }

    /**
     * Writes one verdict. Returns whether the entry left {@code QUARANTINED}.
     *
     * <p>Deliberately not {@code @Transactional}: it is called from
     * {@link #reevaluateDue} on the same instance, where an annotation would be
     * bypassed by the proxy anyway and would only read as a guarantee nobody
     * gets. Each {@code save} carries its own transaction from Spring Data, which
     * is exactly the per-entry isolation the sweep wants.
     */
    boolean apply(
            FirewallQuarantineEntity entity, QuarantineReevaluator.Verdict verdict, Instant now) {

        if (verdict.holds()) {
            entity.setLastEvaluatedAt(now);
            entity.setNextEvaluationAt(verdict.nextEvaluationAt());
            entity.setDecisionReason(trim(verdict.note(), 1000));
            entity.setUpdatedAt(now);
            entries.save(entity);
            return false;
        }

        FirewallQuarantineState target = verdict.outcome() == QuarantineReevaluator.Outcome.RELEASE
                ? FirewallQuarantineState.RELEASED
                : FirewallQuarantineState.BLOCKED;
        QuarantineDecision decision = verdict.exemptionId() == null
                ? QuarantineDecision.automatic(verdict.resolution(), verdict.note())
                : QuarantineDecision.byExemption(
                        verdict.exemptionId(), QuarantineDecision.SYSTEM, verdict.note());

        entries.save(applyDecision(entity, decision, target, now));
        log.info("Quarantine {} {} in {}: {} ({})",
                verdict.outcome() == QuarantineReevaluator.Outcome.RELEASE ? "released" : "blocked",
                entity.getComponentKey(), entity.getRepositoryName(),
                verdict.resolution(), verdict.note());
        return true;
    }

    @Override
    @Transactional
    public int invalidatePolicy(UUID policyId) {
        if (policyId == null) {
            return 0;
        }
        Instant now = Instant.now();
        List<FirewallQuarantineEntity> affected =
                entries.findByPolicyIdAndState(policyId, FirewallQuarantineState.QUARANTINED);
        for (FirewallQuarantineEntity entity : affected) {
            // Due now, not released now: what the edited policy says is the
            // sweep's business, and deciding it here would duplicate the engine.
            entity.setNextEvaluationAt(now);
            entity.setUpdatedAt(now);
        }
        entries.saveAll(affected);
        if (!affected.isEmpty()) {
            log.info("Policy {} changed: {} quarantined entries scheduled for immediate re-evaluation",
                    policyId, affected.size());
        }
        return affected.size();
    }

    // ------------------------------------------------------------------

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String trim(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
