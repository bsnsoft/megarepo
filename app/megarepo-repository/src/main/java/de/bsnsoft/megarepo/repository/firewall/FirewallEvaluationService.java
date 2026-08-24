package de.bsnsoft.megarepo.repository.firewall;

import de.bsnsoft.megarepo.core.firewall.FirewallMode;
import de.bsnsoft.megarepo.database.entity.AssetEntity;
import de.bsnsoft.megarepo.database.entity.ComponentEntity;
import de.bsnsoft.megarepo.database.entity.FirewallRepositoryConfigEntity;
import de.bsnsoft.megarepo.database.repository.AssetJpaRepository;
import de.bsnsoft.megarepo.database.repository.ComponentJpaRepository;
import de.bsnsoft.megarepo.database.repository.FirewallRepositoryConfigJpaRepository;
import de.bsnsoft.megarepo.repository.advisory.AdvisoryFinding;
import de.bsnsoft.megarepo.repository.advisory.AdvisoryLookupService;
import de.bsnsoft.megarepo.repository.firewall.identity.ComponentIdentity;
import de.bsnsoft.megarepo.repository.firewall.identity.PurlBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The observation path of the repository firewall: identify the component behind
 * a download, ask the local advisory store what is known about it, and write
 * down the answer.
 *
 * <h2>What it does not do</h2>
 *
 * It does not block, quarantine, delay or alter a download, and it has no code
 * path that could — every evaluation it produces carries
 * {@link FirewallDecision#notEvaluated()}. Nothing here decides whether a
 * finding is <em>acceptable</em>, only whether one exists; that judgement
 * belongs to {@link FirewallPolicyEvaluator} and reaches a download only through
 * {@link FirewallEnforcementService}.
 *
 * <p>Consequently the only thing this class writes to
 * {@code firewall_violation} is "these advisories name this component", under
 * {@link de.bsnsoft.megarepo.core.firewall.FirewallRuleType#ADVISORY_MATCH} and
 * always with action {@code WARN}.
 *
 * <h2>Modes</h2>
 *
 * <ul>
 *   <li>{@code OFF} — returns before touching the database. This is the mode a
 *       repository with no {@code firewall_repository_config} row gets by
 *       default (see {@link FirewallAuditProperties#defaultMode()}), so
 *       deploying this code observes nothing until someone opts in.</li>
 *   <li>{@code AUDIT} — evaluate and record.</li>
 *   <li>{@code QUARANTINE} — <b>observed exactly like AUDIT here.</b> A
 *       repository asking for enforcement only gets it when the global
 *       enforcement switch is on, and then the request path never reaches this
 *       class for that download — {@link FirewallEnforcementService} has already
 *       evaluated and recorded it before the response was written. Whenever this
 *       class does see a QUARANTINE download, enforcement was off, and the row
 *       it writes says so ({@code enforced=false}).</li>
 * </ul>
 *
 * <h2>No network, no exceptions</h2>
 *
 * Every query this class issues goes to the local database:
 * {@code firewall_repository_config}, {@code assets}, {@code components} and —
 * through {@link AdvisoryLookupService} — {@code advisory}. Advisory feeds are
 * pulled by a background task; nothing here talks to them. The customer's rule
 * is explicit: never block a request thread on a network call.
 *
 * <p>{@link #evaluateDownload} never throws. Every failure becomes
 * {@link FirewallEvaluation.Outcome#FAILED} and a log line. A firewall defect
 * must not be able to turn a working download into a 500 — and because the
 * request-path hook runs after the response has been written
 * ({@link FirewallDownloadObserver}), it structurally cannot.
 */
@Service
public class FirewallEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(FirewallEvaluationService.class);

    private final FirewallRepositoryConfigJpaRepository repositoryConfigs;
    private final AssetJpaRepository assets;
    private final ComponentJpaRepository components;
    private final PurlBuilder purlBuilder;
    private final AdvisoryLookupService advisories;
    private final FirewallViolationRecorder recorder;
    private final FirewallAuditProperties properties;

    public FirewallEvaluationService(
            FirewallRepositoryConfigJpaRepository repositoryConfigs,
            AssetJpaRepository assets,
            ComponentJpaRepository components,
            PurlBuilder purlBuilder,
            AdvisoryLookupService advisories,
            FirewallViolationRecorder recorder,
            FirewallAuditProperties properties) {
        this.repositoryConfigs = repositoryConfigs;
        this.assets = assets;
        this.components = components;
        this.purlBuilder = purlBuilder;
        this.advisories = advisories;
        this.recorder = recorder;
        this.properties = properties;
    }

    /**
     * Evaluates one already-served download and records what was found.
     *
     * <p>Runs on whatever thread calls it — in production a pool thread owned by
     * {@link FirewallDownloadObserver}, in tests the test thread. Never throws.
     *
     * @param repositoryId repository the artifact was served from
     * @param repositoryName its name, for the audit trail
     * @param path artifact path within the repository
     * @param context who requested it
     * @return what happened; never null, and never something that can withhold
     *     content
     */
    public FirewallEvaluation evaluateDownload(
            UUID repositoryId, String repositoryName, String path, FirewallRequestContext context) {

        FirewallRepositorySettings settings = FirewallRepositorySettings.fallback(FirewallMode.OFF);
        try {
            if (!properties.enabled()) {
                return outcome(repositoryId, repositoryName, path, settings, FirewallEvaluation.Outcome.DISABLED);
            }
            if (repositoryId == null || path == null) {
                return outcome(repositoryId, repositoryName, path, settings, FirewallEvaluation.Outcome.NO_COMPONENT);
            }

            settings = resolveSettings(repositoryId);
            if (!settings.evaluates()) {
                return outcome(repositoryId, repositoryName, path, settings, FirewallEvaluation.Outcome.MODE_OFF);
            }
            if (settings.enforces()) {
                log.debug("Repository {} is configured for QUARANTINE but enforcement is off; "
                        + "observing only and serving anyway", repositoryName);
            }

            FirewallEvaluation inspection = inspect(repositoryId, repositoryName, path, settings, null);
            if (inspection.outcome() != FirewallEvaluation.Outcome.MATCHED) {
                return inspection;
            }

            boolean written = recorder.record(
                    repositoryId, repositoryName, inspection.identity(), settings,
                    inspection.findings(), context);
            return inspection.withOutcome(written
                    ? FirewallEvaluation.Outcome.RECORDED
                    : FirewallEvaluation.Outcome.SUPPRESSED);

        } catch (RuntimeException e) {
            // The download is already on the wire. Whatever broke here is a
            // firewall problem and must stay one.
            log.warn("Firewall AUDIT evaluation failed for {}/{} — the download was served regardless",
                    repositoryName, path, e);
            return outcome(repositoryId, repositoryName, path, settings, FirewallEvaluation.Outcome.FAILED);
        }
    }

    /**
     * Identifies the component behind a path and looks up what is known about
     * it. Reads only; writes nothing and decides nothing.
     *
     * <p>Shared by the observation path above and by
     * {@link FirewallEnforcementService}, so that "what is this component and
     * what is wrong with it" is answered by one piece of code no matter which
     * path asks. The two differ in what they do with the answer, not in how they
     * get it.
     *
     * <p>Unlike {@link #evaluateDownload} this method does <em>not</em> swallow
     * exceptions. It is called from both a context where a failure means "the
     * audit trail loses a row" and one where it means "the fail mode has to
     * decide", and only the caller knows which.
     *
     * @param preExistingBefore components whose asset was stored before this
     *     instant count as already present in the repository. Null means "no
     *     watermark", which marks nothing as pre-existing — correct for the
     *     observation path, which does not use the flag at all.
     * @return an evaluation whose outcome is one of {@code NO_COMPONENT},
     *     {@code UNRESOLVABLE_IDENTITY}, {@code CLEAN} or {@code MATCHED}
     */
    public FirewallEvaluation inspect(
            UUID repositoryId,
            String repositoryName,
            String path,
            FirewallRepositorySettings settings,
            Instant preExistingBefore) {

        Optional<AssetEntity> asset = assets.findByRepositoryIdAndPath(repositoryId, path);
        Optional<ComponentEntity> component = asset
                .map(AssetEntity::getComponentId)
                .flatMap(components::findById);
        if (component.isEmpty()) {
            // Checksums, metadata, index pages and anything not yet attached
            // to a component. Not a finding, not an error.
            return outcome(repositoryId, repositoryName, path, settings, FirewallEvaluation.Outcome.NO_COMPONENT);
        }

        boolean preExisting = isPreExisting(asset.orElse(null), preExistingBefore);
        String sha256 = asset.map(AssetEntity::getChecksumSha256).orElse(null);
        ComponentIdentity identity = purlBuilder.identify(component.get(), sha256);

        if (!identity.isResolvable()) {
            // Hash or unidentified: no advisory feed indexes those, so there is
            // nothing to look up and the observation path records nothing. The
            // enforcement path does carry on from here — an unidentifiable
            // component is exactly what the UNKNOWN_COMPONENT rule is about, and
            // that rule exists as of Phase 2.
            return new FirewallEvaluation(
                    repositoryId, repositoryName, path, settings, identity, List.of(),
                    FirewallEvaluation.Outcome.UNRESOLVABLE_IDENTITY,
                    preExisting, FirewallDecision.notEvaluated());
        }

        List<AdvisoryFinding> findings = advisories.findAdvisories(identity);
        return new FirewallEvaluation(
                repositoryId, repositoryName, path, settings, identity, findings,
                findings.isEmpty()
                        ? FirewallEvaluation.Outcome.CLEAN
                        : FirewallEvaluation.Outcome.MATCHED,
                preExisting, FirewallDecision.notEvaluated());
    }

    /**
     * Whether the artifact behind this path was already stored in the repository
     * before the watermark.
     *
     * <p>The asset's {@code created_at} is the fact being read: a proxy cache
     * hit serves a row that was written when the artifact was first pulled, a
     * cache miss writes the row during the very request being evaluated, and a
     * hosted artifact's row dates from its upload. So "the asset is older than
     * the moment enforcement was switched on" is exactly "this was already in
     * the repository when the operator flipped the switch".
     *
     * <p>A null watermark means the caller is not asking the question at all
     * (the observation path) and answers {@code false}. An asset with no
     * timestamp answers {@code true}: when the age of a stored artifact cannot
     * be established, the only safe reading is that it was already there.
     */
    private static boolean isPreExisting(AssetEntity asset, Instant preExistingBefore) {
        if (preExistingBefore == null) {
            return false;
        }
        if (asset == null || asset.getCreatedAt() == null) {
            return true;
        }
        return asset.getCreatedAt().isBefore(preExistingBefore);
    }

    /**
     * The repository's firewall configuration, falling back to the configured
     * default when no row exists.
     *
     * <p>V11 states that an absent row means "not configured" and is resolved by
     * the application; Phase 2 backfills an explicit row per repository.
     */
    public FirewallRepositorySettings resolveSettings(UUID repositoryId) {
        return repositoryConfigs
                .findById(repositoryId)
                .map(FirewallEvaluationService::toSettings)
                .orElseGet(() -> FirewallRepositorySettings.fallback(properties.defaultMode()));
    }

    private static FirewallRepositorySettings toSettings(FirewallRepositoryConfigEntity entity) {
        return new FirewallRepositorySettings(
                entity.getMode(), entity.getFailMode(), entity.getPolicyId(), true);
    }

    private static FirewallEvaluation outcome(
            UUID repositoryId,
            String repositoryName,
            String path,
            FirewallRepositorySettings settings,
            FirewallEvaluation.Outcome outcome) {
        return new FirewallEvaluation(
                repositoryId, repositoryName, path, settings, null, List.of(), outcome);
    }
}
