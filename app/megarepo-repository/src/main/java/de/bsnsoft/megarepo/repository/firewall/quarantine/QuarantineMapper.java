package de.bsnsoft.megarepo.repository.firewall.quarantine;

import de.bsnsoft.megarepo.database.entity.FirewallQuarantineEntity;
import de.bsnsoft.megarepo.repository.advisory.AdvisoryFinding;
import de.bsnsoft.megarepo.repository.firewall.FirewallEvaluation;
import de.bsnsoft.megarepo.repository.firewall.FirewallRequestContext;
import de.bsnsoft.megarepo.repository.firewall.FirewallRuleViolation;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Translates between {@code firewall_quarantine} rows and the value types the
 * rest of the application sees.
 *
 * <p>Two directions and one snapshot builder:
 *
 * <ul>
 *   <li>{@link #toEntry(FirewallQuarantineEntity)} — an entity becomes a
 *       {@link FirewallQuarantineEntry} before it leaves the service. The queue
 *       screen and the request path both read entries, and handing either a
 *       managed entity out of a transaction is a lazy-load trap and an
 *       accidental write path.</li>
 *   <li>{@link #snapshot} — the JSONB blob stored in {@code evaluation}. It is
 *       the only record of <em>why</em> a component was held that survives the
 *       policy being edited afterwards, which is exactly the situation an
 *       operator is in when they open the queue.</li>
 * </ul>
 *
 * <p>A bean rather than a static utility so the service can be constructed with
 * a stub in a unit test, and so a future format-specific enrichment of the
 * snapshot has somewhere to go.
 */
@Component
public class QuarantineMapper {

    /** How many advisory ids go into one snapshot before the list is truncated. */
    private static final int MAX_SNAPSHOT_ADVISORIES = 50;

    /** The row as everything outside the persistence layer sees it. */
    public FirewallQuarantineEntry toEntry(FirewallQuarantineEntity entity) {
        if (entity == null) {
            return null;
        }
        return new FirewallQuarantineEntry(
                entity.getId(),
                entity.getRepositoryId(),
                entity.getRepositoryName(),
                entity.getComponentKey(),
                entity.getPath(),
                entity.getState(),
                entity.getReasonCode(),
                entity.getResolution(),
                entity.getPolicyId(),
                entity.getEvaluation(),
                entity.getFirstSeen(),
                entity.getLastSeen(),
                entity.getHitCount(),
                entity.getLastEvaluatedAt(),
                entity.getNextEvaluationAt(),
                entity.getDecidedAt(),
                entity.getDecidedBy(),
                entity.getDecisionReason(),
                entity.getExemptionId());
    }

    /**
     * The decision snapshot written to {@code firewall_quarantine.evaluation}.
     *
     * <p>Flat, small and self-describing: matched rules with their action and
     * sentence, the advisory ids behind them, and who asked for the component.
     * Nothing here is ever queried — it is read by a human looking at one entry —
     * so it carries the sentences rather than the ids alone.
     *
     * @param evaluation the decision that produced the hold
     * @param context who tripped it, or null when the sweep produced the update
     */
    public Map<String, Object> snapshot(
            FirewallEvaluation evaluation, FirewallRequestContext context) {

        Map<String, Object> snapshot = new LinkedHashMap<>();
        if (evaluation == null) {
            return snapshot;
        }

        snapshot.put("outcome", String.valueOf(evaluation.outcome()));
        if (evaluation.componentKey() != null) {
            snapshot.put("componentKey", evaluation.componentKey());
        }
        if (evaluation.path() != null) {
            snapshot.put("path", evaluation.path());
        }
        if (evaluation.decision() != null) {
            snapshot.put("decisionReason", String.valueOf(evaluation.decision().reason()));
            if (evaluation.decision().policyName() != null) {
                snapshot.put("policyName", evaluation.decision().policyName());
            }
            List<Map<String, Object>> rules = new ArrayList<>();
            for (FirewallRuleViolation violation : evaluation.decision().violations()) {
                Map<String, Object> rule = new LinkedHashMap<>();
                rule.put("ruleType", String.valueOf(violation.ruleType()));
                rule.put("action", String.valueOf(violation.action()));
                rule.put("reason", violation.reason());
                if (!violation.advisoryIds().isEmpty()) {
                    rule.put("advisoryIds", violation.advisoryIds());
                }
                rules.add(rule);
            }
            if (!rules.isEmpty()) {
                snapshot.put("rules", rules);
            }
        }

        List<String> advisoryIds = advisoryIds(evaluation.findings());
        if (!advisoryIds.isEmpty()) {
            snapshot.put("advisoryIds", advisoryIds);
        }

        if (context != null) {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("user", context.user());
            request.put("clientIp", context.clientIp());
            request.put("method", context.method());
            if (context.viaRepository() != null) {
                request.put("viaRepository", context.viaRepository());
            }
            snapshot.put("request", request);
        }
        return snapshot;
    }

    /**
     * Every advisory id behind the findings, sorted, de-duplicated and bounded.
     *
     * <p>Bounded because a snapshot is diagnostic detail on a row an operator
     * reads, and a component named by four hundred advisories would otherwise put
     * four hundred strings into a JSONB column for no additional insight.
     */
    private static List<String> advisoryIds(List<AdvisoryFinding> findings) {
        TreeSet<String> ids = new TreeSet<>();
        for (AdvisoryFinding finding : findings) {
            ids.addAll(finding.advisoryIds());
        }
        if (ids.size() <= MAX_SNAPSHOT_ADVISORIES) {
            return List.copyOf(ids);
        }
        return List.copyOf(new ArrayList<>(ids).subList(0, MAX_SNAPSHOT_ADVISORIES));
    }
}
