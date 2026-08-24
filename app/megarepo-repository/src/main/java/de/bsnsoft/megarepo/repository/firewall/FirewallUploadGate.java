package de.bsnsoft.megarepo.repository.firewall;

import de.bsnsoft.megarepo.core.firewall.FirewallMode;
import de.bsnsoft.megarepo.core.repository.RepositoryConfig;
import de.bsnsoft.megarepo.core.repository.RepositoryType;
import de.bsnsoft.megarepo.database.entity.AssetEntity;
import de.bsnsoft.megarepo.database.entity.ComponentEntity;
import de.bsnsoft.megarepo.database.repository.AssetJpaRepository;
import de.bsnsoft.megarepo.database.repository.ComponentJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * The upload side of the enforcement path, as one call the router can make.
 *
 * <h2>Why a gate and not the evaluator directly</h2>
 *
 * {@link FirewallUploadEvaluator} judges a component it is handed. Turning "a PUT
 * finished writing to this path" into that component means reading the asset row
 * the format handler just created and the component it hangs off — two queries
 * that belong neither in a REST controller nor in a policy evaluator. This class
 * is that seam, and it is also where the "costs nothing when switched off"
 * guarantee is kept: an installation with the master switch off, or a repository
 * that is not in {@code QUARANTINE}, leaves here having issued at most one
 * indexed read and usually none.
 *
 * <h2>Why the evaluation happens after the bytes are written</h2>
 *
 * Because the component does not exist until then. Identity comes from the
 * coordinates the format handler extracts while storing the artifact — the layout
 * rules for that live in the format module and nowhere else — so evaluating
 * before the write would mean re-implementing Maven's, npm's, PyPI's and NuGet's
 * path grammars in the router, which is the class of duplication that eventually
 * disagrees with the real one.
 *
 * <p>The consequence is that a refused upload has to be <em>retracted</em>, and
 * the router does that through the format handler's own delete before it writes
 * the 403. That is a deliberate trade: a brief window in which a denied artifact
 * exists on disk, against a permanent risk of the firewall and the storage layer
 * disagreeing about what a path means. The window is not a hole — the upload is
 * answered with a 403 and the asset is gone before the response is written, so no
 * consumer can have resolved it through the API in between.
 *
 * <h2>No timeout</h2>
 *
 * Unlike a download this runs inline on the request thread. A publish has already
 * paid for a body transfer and a blob write, so the same handful of indexed reads
 * a download performs is not what will make it slow; and wrapping it in the
 * enforcement pool would let a saturated pool refuse a release for a reason that
 * has nothing to do with the artifact — a fail-closed repository would turn pool
 * pressure into failed deployments. If a bound is wanted later it belongs on the
 * pool, not here.
 */
@Service
public class FirewallUploadGate {

    private static final Logger log = LoggerFactory.getLogger(FirewallUploadGate.class);

    private final FirewallEnforcementSettingsService enforcementSettings;
    private final FirewallEvaluationService evaluationService;
    private final FirewallUploadEvaluator uploadEvaluator;
    private final FirewallViolationRecorder recorder;
    private final AssetJpaRepository assets;
    private final ComponentJpaRepository components;

    public FirewallUploadGate(
            FirewallEnforcementSettingsService enforcementSettings,
            FirewallEvaluationService evaluationService,
            FirewallUploadEvaluator uploadEvaluator,
            FirewallViolationRecorder recorder,
            AssetJpaRepository assets,
            ComponentJpaRepository components) {
        this.enforcementSettings = enforcementSettings;
        this.evaluationService = evaluationService;
        this.uploadEvaluator = uploadEvaluator;
        this.recorder = recorder;
        this.assets = assets;
        this.components = components;
    }

    /**
     * Judges the artifact a PUT has just written.
     *
     * <p>Never throws: a firewall fault publishes the artifact, the same way a
     * firewall fault serves one.
     *
     * @param repository the repository that actually stored it — through a group,
     *     the writable member, never the group itself
     * @param path the artifact path that was written
     * @param context who published it
     * @return the verdict. {@link FirewallEvaluation#blocked()} means the caller
     *     has to retract the artifact and answer 403
     */
    public FirewallEvaluation evaluate(
            RepositoryConfig repository, String path, FirewallRequestContext context) {

        FirewallRepositorySettings settings = FirewallRepositorySettings.fallback(FirewallMode.OFF);
        try {
            if (repository == null || path == null
                    || repository.type() != RepositoryType.HOSTED
                    || !enforcementSettings.enforcementEnabled()) {
                return notEnforcing(repository, path, settings);
            }

            settings = evaluationService.resolveSettings(repository.id());
            if (!settings.enforces()) {
                return notEnforcing(repository, path, settings);
            }

            Optional<AssetEntity> asset = assets.findByRepositoryIdAndPath(repository.id(), path);
            ComponentEntity component = asset
                    .map(AssetEntity::getComponentId)
                    .flatMap(components::findById)
                    .orElse(null);
            if (component == null) {
                // A checksum, a metadata file, or a format that attaches no
                // component to this path. There is nothing for a policy to judge,
                // and refusing on "we could not identify it" would break every
                // maven-metadata.xml a deploy writes.
                return notEnforcing(repository, path, settings);
            }

            FirewallEvaluation verdict = uploadEvaluator.evaluate(
                    repository.id(),
                    repository.name(),
                    repository.type(),
                    path,
                    component,
                    asset.map(AssetEntity::getChecksumSha256).orElse(null),
                    context);
            record(verdict, context);
            return verdict;

        } catch (RuntimeException e) {
            log.warn("Repository firewall upload evaluation failed for {}/{} — the upload was kept",
                    repository == null ? "?" : repository.name(), path, e);
            return notEnforcing(repository, path, settings)
                    .withOutcome(FirewallEvaluation.Outcome.FAILED);
        }
    }

    /**
     * Writes what the policy concluded about the publish to
     * {@code firewall_violation}.
     *
     * <p>{@link FirewallUploadEvaluator} decides and holds; it does not record.
     * Doing it here keeps the audit trail for an upload the same shape, the same
     * table and the same de-duplication as the one for a download — an operator
     * asking "what has this policy refused?" should not have to know which
     * direction the artifact was travelling.
     *
     * <p>Inline rather than off a pool thread, unlike the download path. A
     * download is recorded after the bytes are already on the wire, so the write
     * must not delay the client; a publish is still being answered, the client is
     * already paying for a blob write, and a refused upload whose reason never
     * reached the log is the one case where the record matters most.
     *
     * <p>A failure changes nothing: the verdict has been reached, and a log write
     * must not be able to turn a refusal into an acceptance.
     */
    private void record(FirewallEvaluation verdict, FirewallRequestContext context) {
        if (!verdict.enforcementEvaluated()) {
            return;
        }
        try {
            recorder.recordDecision(verdict, context);
        } catch (RuntimeException e) {
            log.warn("Could not record the firewall decision for the upload {}/{}",
                    verdict.repositoryName(), verdict.path(), e);
        }
    }

    private static FirewallEvaluation notEnforcing(
            RepositoryConfig repository, String path, FirewallRepositorySettings settings) {
        return new FirewallEvaluation(
                repository == null ? null : repository.id(),
                repository == null ? null : repository.name(),
                path,
                settings,
                null,
                List.of(),
                FirewallEvaluation.Outcome.NOT_ENFORCING);
    }
}
