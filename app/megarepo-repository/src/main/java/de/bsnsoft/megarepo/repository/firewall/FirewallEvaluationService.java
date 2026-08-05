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

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 1 of the repository firewall: identify the component behind a download,
 * ask the local advisory store what is known about it, and write down the
 * answer.
 *
 * <h2>What it does not do</h2>
 *
 * It does not block, quarantine, delay or alter a download, and it has no code
 * path that could. There is no policy engine yet either: nothing here decides
 * whether a finding is <em>acceptable</em>, only whether one exists. The
 * customer's Phase 1 scope is "purl identification + advisory sources + AUDIT
 * mode only, no blocking", and AUDIT is defined as "record violations, serve
 * anyway".
 *
 * <p>Consequently the only thing that reaches {@code firewall_violation} is
 * "these advisories name this component", under
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
 *   <li>{@code QUARANTINE} — <b>treated exactly like AUDIT.</b> Enforcement is
 *       Phase 2. A repository configured for QUARANTINE today is observed and
 *       recorded and <em>nothing of its content is withheld</em>; the recorded
 *       row carries {@code mode=QUARANTINE} together with
 *       {@code enforced=false} so the gap is visible in the data and not only
 *       in this comment.</li>
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
            if (settings.enforcementDeferred()) {
                log.debug("Repository {} is configured for QUARANTINE; Phase 1 observes only and serves anyway",
                        repositoryName);
            }

            Optional<AssetEntity> asset = assets.findByRepositoryIdAndPath(repositoryId, path);
            Optional<ComponentEntity> component = asset
                    .map(AssetEntity::getComponentId)
                    .flatMap(components::findById);
            if (component.isEmpty()) {
                // Checksums, metadata, index pages and anything not yet attached
                // to a component. Not a finding, not an error.
                return outcome(repositoryId, repositoryName, path, settings, FirewallEvaluation.Outcome.NO_COMPONENT);
            }

            String sha256 = asset.map(AssetEntity::getChecksumSha256).orElse(null);
            ComponentIdentity identity = purlBuilder.identify(component.get(), sha256);

            if (!identity.isResolvable()) {
                // Hash or unidentified: no advisory feed indexes those. Recording
                // it is the UNKNOWN_COMPONENT rule's job, which Phase 2 owns.
                return new FirewallEvaluation(
                        repositoryId, repositoryName, path, settings, identity, List.of(),
                        FirewallEvaluation.Outcome.UNRESOLVABLE_IDENTITY);
            }

            List<AdvisoryFinding> findings = advisories.findAdvisories(identity);
            if (findings.isEmpty()) {
                return new FirewallEvaluation(
                        repositoryId, repositoryName, path, settings, identity, List.of(),
                        FirewallEvaluation.Outcome.CLEAN);
            }

            boolean written =
                    recorder.record(repositoryId, repositoryName, identity, settings, findings, context);
            return new FirewallEvaluation(
                    repositoryId, repositoryName, path, settings, identity, findings,
                    written ? FirewallEvaluation.Outcome.RECORDED : FirewallEvaluation.Outcome.SUPPRESSED);

        } catch (RuntimeException e) {
            // The download is already on the wire. Whatever broke here is a
            // firewall problem and must stay one.
            log.warn("Firewall AUDIT evaluation failed for {}/{} — the download was served regardless",
                    repositoryName, path, e);
            return outcome(repositoryId, repositoryName, path, settings, FirewallEvaluation.Outcome.FAILED);
        }
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
