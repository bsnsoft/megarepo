package de.bsnsoft.megarepo.repository.firewall.exemption;

import de.bsnsoft.megarepo.core.exception.NotFoundException;
import de.bsnsoft.megarepo.core.firewall.FirewallComponentKeyKind;
import de.bsnsoft.megarepo.core.firewall.FirewallExemptionScope;
import de.bsnsoft.megarepo.core.firewall.FirewallExemptionState;
import de.bsnsoft.megarepo.core.firewall.FirewallRuleType;
import de.bsnsoft.megarepo.database.entity.FirewallExemptionEntity;
import de.bsnsoft.megarepo.database.repository.FirewallExemptionJpaRepository;
import de.bsnsoft.megarepo.repository.firewall.identity.ComponentIdentity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The exemption workflow, implemented.
 *
 * <h2>Two very different callers</h2>
 *
 * {@link #findApplicable} sits on the download path: one indexed query, no
 * network, and — the part that is not an optimisation — it does not throw. An
 * exemption store that cannot be read is a firewall that does not know about
 * exemptions, which refuses a download somebody had permission for. That is bad
 * and visible. An exception thrown from here would instead take the whole
 * evaluation down, and rule 4 of the phase ("a firewall fault serves the
 * artifact") turns that into serving everything. Returning "no exemption" is the
 * conservative half of the failure.
 *
 * <p>Everything else is an operator action behind the admin API, where an
 * illegal transition must be an error rather than a silently absorbed no-op.
 *
 * <h2>The legacy probe</h2>
 *
 * The V8 coordinate forms are only worth building on an installation that
 * actually has {@code LEGACY_COORDINATE} rows, and asking that question is
 * itself a query — one that would double the request path's query count if it
 * ran per download. It is therefore cached for {@link #LEGACY_PROBE_INTERVAL} and
 * re-read after every write this service performs. The staleness is one-sided
 * and harmless: legacy rows are created only by migration V18, which runs before
 * the instance serves anything, so the value can go from true to false (an
 * operator revoked the last one) but never the other way round, and a stale
 * {@code true} costs two extra strings in an {@code IN} list.
 */
@Service
public class DefaultExemptionService implements ExemptionService {

    private static final Logger log = LoggerFactory.getLogger(DefaultExemptionService.class);

    /** How long the "are there legacy rows?" answer is reused. */
    static final Duration LEGACY_PROBE_INTERVAL = Duration.ofSeconds(30);

    /** {@code firewall_exemption.justification} is {@code VARCHAR(2000)} (V17). */
    private static final int MAX_JUSTIFICATION_LENGTH = 2000;

    private final FirewallExemptionJpaRepository exemptions;
    private final ExemptionProperties properties;
    private final Clock clock;

    @PersistenceContext
    private EntityManager entityManager;

    /** Cached answer of {@code countByKeyKind(LEGACY_COORDINATE) > 0}. */
    private volatile boolean legacyKeysPresent = true;

    /** When the cached answer stops being reused; null means "never probed". */
    private volatile Instant legacyProbedUntil;

    @Autowired
    public DefaultExemptionService(
            FirewallExemptionJpaRepository exemptions, ExemptionProperties properties) {
        this(exemptions, properties, Clock.systemUTC());
    }

    /** Visible for tests, which need to move the clock rather than sleep. */
    public DefaultExemptionService(
            FirewallExemptionJpaRepository exemptions, ExemptionProperties properties, Clock clock) {
        this.exemptions = exemptions;
        this.properties = properties == null ? ExemptionProperties.defaults() : properties;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    // ── Request path ────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<FirewallExemption> findApplicable(
            UUID repositoryId, ComponentIdentity identity, Instant at) {

        Instant now = at == null ? clock.instant() : at;
        try {
            ExemptionKeyBuilder.CandidateKeys keys =
                    ExemptionKeyBuilder.candidates(identity, legacyKeysPresent());
            if (keys.isEmpty()) {
                return List.of();
            }
            Set<String> all = keys.all();
            List<FirewallExemptionEntity> rows = exemptions.findApplicable(all, repositoryId, now);
            List<FirewallExemption> applicable = new ArrayList<>(rows.size());
            for (FirewallExemptionEntity row : rows) {
                // The query filters state, repository and expiry; the scope and
                // the key scheme are matched here, where the two key forms are
                // known apart. A row that came back is a candidate, not a match.
                if (!keys.covers(row)) {
                    continue;
                }
                FirewallExemption exemption = ExemptionMapper.toDomain(row);
                if (exemption.isLiveAt(now)) {
                    applicable.add(exemption);
                }
            }
            applicable.sort(BY_SPECIFICITY);
            return List.copyOf(applicable);
        } catch (RuntimeException e) {
            // See the class comment: no exemption is the conservative answer.
            log.warn(
                    "Exemption lookup failed for {} in repository {} — evaluating as if none applied",
                    identity == null ? "<no identity>" : identity.key(),
                    repositoryId,
                    e);
            return List.of();
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<FirewallExemption> findApplicable(
            UUID repositoryId, ComponentIdentity identity, FirewallRuleType ruleType, Instant at) {

        return findApplicable(repositoryId, identity, at).stream()
                .filter(exemption -> exemption.covers(ruleType))
                .findFirst();
    }

    /**
     * Narrowest first.
     *
     * <p>The caller records <em>which</em> exemption let a violation through, and
     * a component covered by both a rule-scoped exemption for this repository and
     * a blanket global one should be reported under the first: it is the decision
     * somebody actually took about this case. Also makes the single-result
     * overload deterministic, which "the first" otherwise would not be.
     */
    private static final Comparator<FirewallExemption> BY_SPECIFICITY = Comparator
            .comparing((FirewallExemption e) -> e.ruleType() == null)
            .thenComparing(e -> e.repositoryId() == null)
            .thenComparing(e -> e.scope() == FirewallExemptionScope.COMPONENT)
            .thenComparing(e -> e.keyKind() == FirewallComponentKeyKind.LEGACY_COORDINATE)
            .thenComparing(e -> e.advisoryIds().isEmpty())
            .thenComparing(FirewallExemption::id, Comparator.nullsLast(Comparator.naturalOrder()));

    // ── Workflow ────────────────────────────────────────────────────────

    @Override
    @Transactional
    public FirewallExemption request(ExemptionRequest request) {
        Objects.requireNonNull(request, "request must not be null");

        String key = ExemptionKeyBuilder.storageKey(request.componentKey(), request.scope());
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("an exemption must name a component");
        }
        if (key.length() > ExemptionKeyBuilder.MAX_KEY_LENGTH) {
            throw new IllegalArgumentException(
                    "component key is longer than " + ExemptionKeyBuilder.MAX_KEY_LENGTH + " characters");
        }
        Instant suggested = request.requestedExpiry();
        if (suggested != null) {
            requireSaneExpiry(suggested);
        }

        FirewallExemptionEntity entity = new FirewallExemptionEntity();
        // Written before the entity so the length guard sees the final text.
        String justification = withSuggestedExpiry(request.justification().trim(), suggested);
        entity.setComponentKey(key);
        // A request never creates a legacy coordinate: only V18 writes those, and
        // only because it cannot produce a purl.
        entity.setKeyKind(FirewallComponentKeyKind.PURL);
        entity.setScopeType(request.scope());
        entity.setRepositoryId(request.repositoryId());
        entity.setRuleType(request.ruleType());
        entity.setAdvisoryIds(request.advisoryIds().toArray(String[]::new));
        entity.setState(FirewallExemptionState.REQUESTED);
        // The requester's suggestion is not an expiry. It is recorded as one only
        // when an approver says so, which is what approve(expiresAt) is for.
        entity.setExpiresAt(null);
        entity.setJustification(justification);
        entity.setRequestedBy(request.requestedBy().trim());
        Instant now = clock.instant();
        entity.setRequestedAt(now);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);

        FirewallExemption saved = ExemptionMapper.toDomain(exemptions.save(entity));
        invalidateLegacyProbe();
        log.info(
                "Exemption requested for {} ({} scope) in {} by {} — rule {}",
                saved.componentKey(),
                saved.scope(),
                saved.repositoryId() == null ? "all repositories" : saved.repositoryId(),
                saved.requestedBy(),
                saved.ruleType() == null ? "every rule" : saved.ruleType());
        return saved;
    }

    @Override
    @Transactional
    public FirewallExemption approve(UUID id, String approver, String note, Instant expiresAt) {
        String signedBy = requireApprover(approver);
        FirewallExemptionEntity entity = load(id);
        requireState(entity, FirewallExemptionState.APPROVED, FirewallExemptionState.REQUESTED);
        if (expiresAt != null) {
            requireSaneExpiry(expiresAt);
        }

        Instant now = clock.instant();
        entity.setState(FirewallExemptionState.APPROVED);
        entity.setApprovedBy(signedBy);
        entity.setApprovedAt(now);
        entity.setDecisionNote(trimToNull(note));
        entity.setExpiresAt(expiresAt);
        // A fresh grant has not been announced yet, whatever the row said before.
        entity.setExpiryNotifiedAt(null);
        entity.setUpdatedAt(now);

        FirewallExemption saved = ExemptionMapper.toDomain(exemptions.save(entity));
        invalidateLegacyProbe();
        log.warn(
                "Exemption {} for {} approved by {} — {}",
                saved.id(),
                saved.componentKey(),
                signedBy,
                saved.isPermanent() ? "never expires" : "expires " + saved.expiresAt());
        return saved;
    }

    @Override
    @Transactional
    public FirewallExemption reject(UUID id, String approver, String note) {
        return decide(id, approver, note, FirewallExemptionState.REJECTED, FirewallExemptionState.REQUESTED);
    }

    @Override
    @Transactional
    public FirewallExemption revoke(UUID id, String approver, String note) {
        return decide(id, approver, note, FirewallExemptionState.REVOKED, FirewallExemptionState.APPROVED);
    }

    private FirewallExemption decide(
            UUID id,
            String approver,
            String note,
            FirewallExemptionState target,
            FirewallExemptionState... legalFrom) {

        String signedBy = requireApprover(approver);
        FirewallExemptionEntity entity = load(id);
        requireState(entity, target, legalFrom);

        Instant now = clock.instant();
        entity.setState(target);
        entity.setApprovedBy(signedBy);
        entity.setApprovedAt(now);
        entity.setDecisionNote(trimToNull(note));
        entity.setUpdatedAt(now);
        // expiresAt is deliberately left as it stands. Backdating it on a
        // revocation would make the row claim the exemption lapsed by itself,
        // which is the reason REVOKED exists as its own state at all.

        FirewallExemption saved = ExemptionMapper.toDomain(exemptions.save(entity));
        invalidateLegacyProbe();
        log.warn("Exemption {} for {} {} by {}",
                saved.id(), saved.componentKey(), target.name().toLowerCase(Locale.ROOT), signedBy);
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<FirewallExemption> find(UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        return exemptions.findById(id).map(ExemptionMapper::toDomain);
    }

    /**
     * The management list.
     *
     * <p>Built with the Criteria API rather than as a JPQL query with
     * {@code (:filter IS NULL OR …)} branches: PostgreSQL rejects a bare
     * parameter in an {@code IS NULL} test with "could not determine data type of
     * parameter" — the same trap {@code AdvisoryAffectedJpaRepository} documents.
     * A criteria query simply omits the predicate, which is also the plan the
     * database would have wanted.
     */
    @Override
    @Transactional(readOnly = true)
    public Page<FirewallExemption> list(ExemptionQuery query, Pageable pageable) {
        ExemptionQuery effective = query == null ? ExemptionQuery.all() : query;
        Pageable page = pageable == null ? Pageable.unpaged() : pageable;

        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<FirewallExemptionEntity> select =
                builder.createQuery(FirewallExemptionEntity.class);
        Root<FirewallExemptionEntity> root = select.from(FirewallExemptionEntity.class);
        select.where(filters(builder, root, effective));
        select.orderBy(builder.desc(root.get("requestedAt")), builder.asc(root.get("id")));

        var typed = entityManager.createQuery(select);
        if (page.isPaged()) {
            typed.setFirstResult((int) page.getOffset());
            typed.setMaxResults(page.getPageSize());
        }
        List<FirewallExemption> content = ExemptionMapper.toDomain(typed.getResultList());

        CriteriaQuery<Long> count = builder.createQuery(Long.class);
        Root<FirewallExemptionEntity> countRoot = count.from(FirewallExemptionEntity.class);
        count.select(builder.count(countRoot));
        count.where(filters(builder, countRoot, effective));
        long total = entityManager.createQuery(count).getSingleResult();

        return new PageImpl<>(content, page, total);
    }

    private static Predicate[] filters(
            CriteriaBuilder builder, Root<FirewallExemptionEntity> root, ExemptionQuery query) {

        List<Predicate> predicates = new ArrayList<>(4);
        if (query.state() != null) {
            predicates.add(builder.equal(root.get("state"), query.state()));
        }
        if (query.repositoryId() != null) {
            // Exact match, global exemptions excluded — see ExemptionQuery: a
            // management list and an applicability check are different questions.
            predicates.add(builder.equal(root.get("repositoryId"), query.repositoryId()));
        }
        String fragment = trimToNull(query.componentKeyContains());
        if (fragment != null) {
            predicates.add(builder.like(
                    builder.lower(root.get("componentKey")),
                    "%" + fragment.toLowerCase(Locale.ROOT) + "%"));
        }
        if (query.expiringOnly()) {
            predicates.add(builder.isNotNull(root.get("expiresAt")));
        }
        return predicates.toArray(Predicate[]::new);
    }

    @Override
    @Transactional(readOnly = true)
    public ExemptionSummary summary() {
        return new ExemptionSummary(
                exemptions.countByState(FirewallExemptionState.REQUESTED),
                exemptions.countByState(FirewallExemptionState.APPROVED),
                exemptions.countByState(FirewallExemptionState.REJECTED),
                exemptions.countByState(FirewallExemptionState.EXPIRED),
                exemptions.countByState(FirewallExemptionState.REVOKED),
                exemptions.countByKeyKind(FirewallComponentKeyKind.LEGACY_COORDINATE));
    }

    // ── Sweeps ──────────────────────────────────────────────────────────

    @Override
    @Transactional
    public int expireLapsed(Instant now) {
        Instant at = now == null ? clock.instant() : now;
        List<FirewallExemptionEntity> lapsed = exemptions.findExpired(at);
        if (lapsed.isEmpty()) {
            return 0;
        }
        for (FirewallExemptionEntity entity : lapsed) {
            entity.setState(FirewallExemptionState.EXPIRED);
            entity.setUpdatedAt(at);
            log.info(
                    "Exemption {} for {} expired ({}); the component is subject to the policy again",
                    entity.getId(), entity.getComponentKey(), entity.getExpiresAt());
        }
        exemptions.saveAll(lapsed);
        invalidateLegacyProbe();
        return lapsed.size();
    }

    @Override
    @Transactional
    public List<FirewallExemption> notifyUpcomingExpiry(Instant now, Duration lead) {
        Instant at = now == null ? clock.instant() : now;
        Duration window = lead == null || lead.isNegative() || lead.isZero()
                ? properties.expiryNoticeLead()
                : lead;

        List<FirewallExemptionEntity> due = exemptions.findDueForExpiryNotice(at, at.plus(window));
        if (due.isEmpty()) {
            return List.of();
        }
        for (FirewallExemptionEntity entity : due) {
            // Stamped before anything else so a failure downstream cannot turn
            // "announce once" into "announce on every sweep".
            entity.setExpiryNotifiedAt(at);
            entity.setUpdatedAt(at);
            log.warn(
                    "Firewall exemption for {} expires at {} (approved by {}): renew it or the "
                            + "component will be subject to the policy again",
                    entity.getComponentKey(), entity.getExpiresAt(), entity.getApprovedBy());
        }
        return ExemptionMapper.toDomain(exemptions.saveAll(due));
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    /** Whether the V8 coordinate forms are worth building. See the class comment. */
    boolean legacyKeysPresent() {
        Instant validUntil = legacyProbedUntil;
        Instant now = clock.instant();
        if (validUntil != null && validUntil.isAfter(now)) {
            return legacyKeysPresent;
        }
        try {
            legacyKeysPresent = exemptions.countByKeyKind(FirewallComponentKeyKind.LEGACY_COORDINATE) > 0;
        } catch (RuntimeException e) {
            // Include them: matching a legacy row that is not there costs a string
            // in an IN list, missing one that is breaks a build.
            legacyKeysPresent = true;
            log.debug("Could not count legacy exemption rows — assuming there are some", e);
        }
        legacyProbedUntil = now.plus(LEGACY_PROBE_INTERVAL);
        return legacyKeysPresent;
    }

    private void invalidateLegacyProbe() {
        legacyProbedUntil = null;
    }

    private FirewallExemptionEntity load(UUID id) {
        if (id == null) {
            throw new NotFoundException("Exemption not found: null");
        }
        return exemptions
                .findById(id)
                .orElseThrow(() -> new NotFoundException("Exemption not found: " + id));
    }

    /**
     * @throws IllegalStateException naming both states, because "invalid state
     *     transition" in a log line is a message that costs somebody an hour
     */
    private static void requireState(
            FirewallExemptionEntity entity,
            FirewallExemptionState target,
            FirewallExemptionState... legalFrom) {

        for (FirewallExemptionState legal : legalFrom) {
            if (entity.getState() == legal) {
                return;
            }
        }
        throw new IllegalStateException(
                "Exemption %s is %s and cannot become %s — only %s can."
                        .formatted(
                                entity.getId(),
                                entity.getState(),
                                target,
                                List.of(legalFrom)));
    }

    /**
     * An exemption without a named approver is an exemption nobody signed, which
     * the V17 CHECK constraint also refuses. Failing here says why.
     */
    private static String requireApprover(String approver) {
        String trimmed = trimToNull(approver);
        if (trimmed == null) {
            throw new IllegalArgumentException("a decision has to name who took it");
        }
        return trimmed;
    }

    private void requireSaneExpiry(Instant expiresAt) {
        Instant now = clock.instant();
        if (!expiresAt.isAfter(now)) {
            throw new IllegalArgumentException(
                    "an expiry in the past would create an exemption that never applies (" + expiresAt + ")");
        }
        Instant ceiling = now.plus(properties.maxValidity());
        if (expiresAt.isAfter(ceiling)) {
            throw new IllegalArgumentException(
                    ("expiry %s is beyond megarepo.firewall.exemption.max-validity (%s). "
                            + "An explicit \"never\" is a different decision and is still available.")
                            .formatted(expiresAt, properties.maxValidity()));
        }
    }

    /**
     * Folds the requester's suggested expiry into their own words.
     *
     * <p>{@code firewall_exemption} has no column for a suggestion, and inventing
     * one is a contract change. {@code decision_note} would be the wrong home —
     * that is what the approver said — so it goes where it came from: the
     * requester's justification, labelled, and only if it fits. Dropping the tail
     * of somebody's reasoning to make room for a date would be the worse trade.
     */
    private static String withSuggestedExpiry(String justification, Instant suggested) {
        if (suggested == null) {
            return justification;
        }
        String suffix = System.lineSeparator() + "Requested expiry: " + suggested;
        if (justification.length() + suffix.length() > MAX_JUSTIFICATION_LENGTH) {
            return justification;
        }
        return justification + suffix;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
