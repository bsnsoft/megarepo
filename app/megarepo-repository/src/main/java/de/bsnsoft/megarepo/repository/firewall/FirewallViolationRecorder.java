package de.bsnsoft.megarepo.repository.firewall;

import de.bsnsoft.megarepo.core.firewall.FirewallAction;
import de.bsnsoft.megarepo.core.firewall.FirewallRuleType;
import de.bsnsoft.megarepo.database.entity.FirewallViolationEntity;
import de.bsnsoft.megarepo.database.repository.FirewallViolationJpaRepository;
import de.bsnsoft.megarepo.repository.advisory.AdvisoryFinding;
import de.bsnsoft.megarepo.repository.advisory.AdvisoryMatch;
import de.bsnsoft.megarepo.repository.firewall.identity.ComponentIdentity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Writes AUDIT findings to {@code firewall_violation}, without letting a busy
 * repository fill the table.
 *
 * <h2>The degeneracy problem</h2>
 *
 * A violation is produced per <em>download</em>, but it describes a
 * <em>component</em>. One CI fleet pulling {@code log4j-core:2.14.1} writes the
 * identical row thousands of times a day, and the audit trail — whose whole
 * purpose is to answer "what would have been blocked here?" — becomes unusable
 * exactly for the components it matters most for. Retention or a UI-side
 * {@code DISTINCT} would not help: the rows still have to be written, indexed
 * and vacuumed first.
 *
 * <h2>The rule</h2>
 *
 * At most one row per {@code (repository, purl, rule_type)} per
 * {@link FirewallAuditProperties#suppressionWindow() suppression window}
 * (default 24h) — <em>unless the set of advisory ids changed</em>, in which case
 * a row is written immediately regardless of the window.
 *
 * <p>The exception is the part that earns the rule. A pure time window would
 * hide a newly published CVE for a component that is already on file behind the
 * window of its predecessor, and on a component downloaded every few minutes
 * that hiding never ends. Comparing the recorded id set means the window
 * suppresses only <em>exact repeats</em>: the same component, in the same
 * repository, found by the same advisories. Anything genuinely new is recorded
 * on the next download.
 *
 * <p>Grain is the component, not the advisory: {@code advisory_ids} is a
 * {@code TEXT[]}, so a component with twelve CVEs is one row with twelve ids
 * rather than twelve rows. That also makes the id-set comparison a single
 * cheap equality check.
 *
 * <p>Two nodes, or two pool threads, evaluating the same component at the same
 * instant can both find no recent row and both insert. The duplicate is
 * harmless in an append-only log and is not worth a lock on the request path's
 * shadow; the check is an anti-flood measure, not a uniqueness constraint.
 *
 * <h2>Observations and decisions</h2>
 *
 * {@link #record} writes an observation: "these advisories name this component",
 * rule type {@code ADVISORY_MATCH}, action {@code WARN}, {@code policy_id} null.
 * A non-null policy id there would claim that a specific policy's rule fired,
 * and on the observation path none did.
 *
 * <p>{@link #recordDecision} writes what the policy engine concluded: one row
 * per matched rule, with that rule's own type, the policy that owns it, and an
 * action that states what <em>happened</em> — {@code BLOCK} only when the
 * download was actually denied. A blocking rule that matched a component which
 * was already in the repository is recorded as {@code WARN} with
 * {@code ruleAction=BLOCK} in the context, because writing {@code BLOCK} for a
 * download that went out would make the log lie about the one thing it exists to
 * record.
 */
@Service
public class FirewallViolationRecorder {

    private static final Logger log = LoggerFactory.getLogger(FirewallViolationRecorder.class);

    /** {@code firewall_violation.purl} is VARCHAR(1000). */
    private static final int MAX_PURL_LENGTH = 1000;

    /** {@code firewall_violation.repository_name} is VARCHAR(200). */
    private static final int MAX_REPOSITORY_NAME_LENGTH = 200;

    /**
     * Findings detailed in {@code request_context}. The advisory ids are all in
     * the {@code advisory_ids} column regardless; this only bounds how much
     * per-finding evidence travels into the JSONB, so that a component with
     * hundreds of matches cannot produce a megabyte-sized audit row.
     */
    static final int MAX_DETAILED_FINDINGS = 50;

    private final FirewallViolationJpaRepository violations;
    private final FirewallAuditProperties properties;

    public FirewallViolationRecorder(
            FirewallViolationJpaRepository violations, FirewallAuditProperties properties) {
        this.violations = violations;
        this.properties = properties;
    }

    /**
     * Records the findings for one component, or recognises them as an exact
     * repeat and writes nothing.
     *
     * @return {@code true} if a row was written
     * @throws NullPointerException if {@code repositoryId} is null — the
     *     de-duplication query cannot express "the repository that no longer
     *     exists", and silently degrading to "write every time" is the failure
     *     mode this class exists to prevent
     */
    @Transactional
    public boolean record(
            UUID repositoryId,
            String repositoryName,
            ComponentIdentity identity,
            FirewallRepositorySettings settings,
            List<AdvisoryFinding> findings,
            FirewallRequestContext context) {

        Objects.requireNonNull(repositoryId, "repositoryId must not be null");
        Objects.requireNonNull(identity, "identity must not be null");
        if (findings == null || findings.isEmpty()) {
            return false;
        }

        String purl = truncate(identity.key(), MAX_PURL_LENGTH);
        String[] advisoryIds = advisoryIds(findings);

        if (isExactRepeat(repositoryId, purl, FirewallRuleType.ADVISORY_MATCH, advisoryIds)) {
            log.trace("Firewall AUDIT: {} in {} already on file with the same advisories", purl, repositoryName);
            return false;
        }

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("phase", "audit");
        header.put("enforced", false);
        header.put("enforcementDeferred", settings.enforcementDeferred(false));
        header.put("failModeApplied", false);

        FirewallViolationEntity entity = new FirewallViolationEntity();
        entity.setRepositoryId(repositoryId);
        entity.setRepositoryName(truncate(repositoryName, MAX_REPOSITORY_NAME_LENGTH));
        entity.setPurl(purl);
        entity.setPolicyId(null);
        entity.setRuleType(FirewallRuleType.ADVISORY_MATCH);
        // WARN, always. BLOCK would claim the download was denied; it was served.
        entity.setAction(FirewallAction.WARN);
        entity.setAdvisoryIds(advisoryIds);
        entity.setOccurredAt(Instant.now());
        entity.setRequestContext(buildContext(header, settings, findings, context));

        violations.save(entity);
        log.info("Firewall AUDIT: {} in {} matched {} advisor(y|ies) — served anyway",
                purl, repositoryName, advisoryIds.length);
        return true;
    }

    /**
     * Records what the policy engine concluded about one download.
     *
     * <p>One row per matched rule, so a component that trips both the CVSS
     * threshold and the malicious-package rule leaves two rows naming two
     * different reasons rather than one row that has to pick. When the policy
     * matched nothing but advisories exist anyway, the plain
     * {@code ADVISORY_MATCH} observation is written instead: an enforcing
     * repository must not have a <em>thinner</em> audit trail than an observing
     * one just because its policy happened to tolerate the finding.
     *
     * <p>Runs after the verdict has been given, off the request thread. A
     * failure here therefore degrades the audit trail and nothing else; the
     * caller ({@link FirewallEnforcementService}) swallows it, because a
     * download that was already allowed or denied must not change outcome
     * because a log write failed.
     *
     * @param evaluation the finished evaluation, decision included
     * @param context who asked for the component
     * @return how many rows were written; 0 when everything was suppressed as an
     *     exact repeat
     */
    @Transactional
    public int recordDecision(FirewallEvaluation evaluation, FirewallRequestContext context) {
        Objects.requireNonNull(evaluation, "evaluation must not be null");
        if (evaluation.repositoryId() == null || evaluation.identity() == null) {
            return 0;
        }
        FirewallDecision decision = evaluation.decision();
        if (decision.violations().isEmpty()) {
            if (!evaluation.hasFindings()) {
                return 0;
            }
            return record(
                    evaluation.repositoryId(),
                    evaluation.repositoryName(),
                    evaluation.identity(),
                    evaluation.settings(),
                    evaluation.findings(),
                    context) ? 1 : 0;
        }

        String purl = truncate(evaluation.identity().key(), MAX_PURL_LENGTH);
        int written = 0;
        for (FirewallRuleViolation violation : decision.violations()) {
            if (writeRuleViolation(evaluation, violation, purl, context)) {
                written++;
            }
        }
        return written;
    }

    private boolean writeRuleViolation(
            FirewallEvaluation evaluation,
            FirewallRuleViolation violation,
            String purl,
            FirewallRequestContext context) {

        FirewallDecision decision = evaluation.decision();
        // Same normalisation as the observation path, so the repeat check
        // compares like with like.
        String[] advisoryIds = sorted(violation.advisoryIds().toArray(String[]::new));

        if (isExactRepeat(evaluation.repositoryId(), purl, violation.ruleType(), advisoryIds)) {
            return false;
        }

        // What happened, not what the rule wanted: a BLOCK rule whose component
        // was grandfathered in was served, and the row has to say so.
        boolean denied = decision.blocked() && violation.blocks();
        FirewallAction recorded = denied ? FirewallAction.BLOCK : FirewallAction.WARN;

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("phase", "enforcement");
        header.put("enforced", true);
        header.put("enforcementDeferred", false);
        header.put("blocked", denied);
        header.put("decision", decision.reason().name());
        header.put("ruleAction", violation.action().name());
        header.put("rule", violation.ruleType().name());
        header.put("ruleReason", violation.reason());
        header.put("preExisting", evaluation.preExisting());
        header.put("failModeApplied", decision.failModeApplied());
        if (decision.policyName() != null) {
            header.put("policy", decision.policyName());
        }

        FirewallViolationEntity entity = new FirewallViolationEntity();
        entity.setRepositoryId(evaluation.repositoryId());
        entity.setRepositoryName(truncate(evaluation.repositoryName(), MAX_REPOSITORY_NAME_LENGTH));
        entity.setPurl(purl);
        entity.setPolicyId(decision.policyId());
        entity.setRuleType(violation.ruleType());
        entity.setAction(recorded);
        entity.setAdvisoryIds(advisoryIds);
        entity.setOccurredAt(Instant.now());
        entity.setRequestContext(
                buildContext(header, evaluation.settings(), evaluation.findings(), context));

        violations.save(entity);
        if (denied) {
            log.warn("Firewall BLOCKED {} in {}: {} — {}",
                    purl, evaluation.repositoryName(), violation.ruleType(), violation.reason());
        } else {
            log.info("Firewall {} {} in {}: {} — {} (served)",
                    evaluation.preExisting() ? "grandfathered" : "warned about",
                    purl, evaluation.repositoryName(), violation.ruleType(), violation.reason());
        }
        return true;
    }

    /**
     * Whether an equivalent row is already on file inside the suppression window.
     *
     * <p>A zero window disables suppression entirely, which is what a comparison
     * run that wants every single observation would configure.
     */
    private boolean isExactRepeat(
            UUID repositoryId, String purl, FirewallRuleType ruleType, String[] advisoryIds) {
        if (properties.suppressionWindow().isZero()) {
            return false;
        }
        Optional<FirewallViolationEntity> previous =
                violations.findFirstByRepositoryIdAndPurlAndRuleTypeOrderByOccurredAtDesc(
                        repositoryId, purl, ruleType);
        if (previous.isEmpty()) {
            return false;
        }
        FirewallViolationEntity last = previous.get();
        Instant cutoff = Instant.now().minus(properties.suppressionWindow());
        if (last.getOccurredAt() == null || last.getOccurredAt().isBefore(cutoff)) {
            return false;
        }
        return Arrays.equals(sorted(last.getAdvisoryIds()), advisoryIds);
    }

    /**
     * Every advisory id behind the findings, sorted and de-duplicated.
     *
     * <p>Sorted so that the comparison against the recorded row is an array
     * equality rather than a set construction, and so that two runs that
     * enumerate the same advisories in a different order do not look like a
     * changed finding.
     */
    private static String[] advisoryIds(List<AdvisoryFinding> findings) {
        Set<String> ids = new TreeSet<>();
        for (AdvisoryFinding finding : findings) {
            ids.addAll(finding.advisoryIds());
        }
        return ids.toArray(String[]::new);
    }

    private static String[] sorted(String[] values) {
        if (values == null) {
            return new String[0];
        }
        return new TreeSet<>(Arrays.asList(values)).toArray(String[]::new);
    }

    /**
     * The evidence for the finding, plus an unambiguous statement of what was
     * done about it.
     *
     * <p>{@code phase}, {@code enforced} and {@code blocked} come from the
     * caller's {@code header} and are written on every row. Without them a
     * QUARANTINE-mode violation reads as "this download was held" whether or not
     * it was, and the audit trail from the observation phase becomes
     * indistinguishable from enforced verdicts.
     */
    private static Map<String, Object> buildContext(
            Map<String, Object> header,
            FirewallRepositorySettings settings,
            List<AdvisoryFinding> findings,
            FirewallRequestContext request) {

        Map<String, Object> context = new LinkedHashMap<>(header);
        context.put("mode", settings.mode().name());
        context.put("failMode", settings.failMode().name());
        context.put("modeSource", settings.explicit() ? "repository-config" : "default");
        if (settings.policyId() != null) {
            context.put("assignedPolicyId", settings.policyId().toString());
        }

        if (request != null) {
            context.put("user", request.user());
            context.put("ip", request.clientIp());
            context.put("path", request.path());
            context.put("method", request.method());
            // Only when there was one. An absent key reads as "asked for
            // directly"; a null-valued key would read as "we did not look".
            if (request.viaRepository() != null) {
                context.put("viaRepository", request.viaRepository());
            }
        }

        context.put("confidence", strongestConfidence(findings));
        context.put("sources", new ArrayList<>(allSources(findings)));
        maxCvss(findings).ifPresent(score -> context.put("maxCvssScore", score));
        context.put("findingCount", findings.size());
        context.put("findings", describe(findings));
        return context;
    }

    private static List<Map<String, Object>> describe(List<AdvisoryFinding> findings) {
        List<Map<String, Object>> described = new ArrayList<>();
        for (AdvisoryFinding finding : findings) {
            if (described.size() >= MAX_DETAILED_FINDINGS) {
                break;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("advisoryId", finding.advisoryId());
            entry.put("severity", finding.severity());
            entry.put("cvssScore", finding.cvssScore());
            entry.put("confidence", finding.confidence().name());
            entry.put("sources", new ArrayList<>(finding.sources()));
            entry.put("advisoryIds", new ArrayList<>(finding.advisoryIds()));
            entry.put("matchedRanges", matchedRanges(finding));
            described.add(entry);
        }
        return described;
    }

    private static List<String> matchedRanges(AdvisoryFinding finding) {
        Set<String> ranges = new LinkedHashSet<>();
        for (AdvisoryMatch match : finding.matches()) {
            if (match.matchedRange() != null) {
                ranges.add(match.matchedRange());
            }
        }
        return new ArrayList<>(ranges);
    }

    private static String strongestConfidence(List<AdvisoryFinding> findings) {
        return findings.stream()
                .map(AdvisoryFinding::confidence)
                .min(Comparable::compareTo)
                .map(Enum::name)
                .orElse(null);
    }

    private static Set<String> allSources(List<AdvisoryFinding> findings) {
        Set<String> sources = new LinkedHashSet<>();
        for (AdvisoryFinding finding : findings) {
            sources.addAll(finding.sources());
        }
        return sources;
    }

    private static Optional<Double> maxCvss(List<AdvisoryFinding> findings) {
        return findings.stream()
                .map(AdvisoryFinding::cvssScore)
                .filter(Objects::nonNull)
                .max(Double::compare);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
