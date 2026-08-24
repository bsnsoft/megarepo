package de.bsnsoft.megarepo.repository.firewall;

import de.bsnsoft.megarepo.core.firewall.FirewallMode;
import de.bsnsoft.megarepo.core.repository.RepositoryType;
import de.bsnsoft.megarepo.database.entity.AssetEntity;
import de.bsnsoft.megarepo.database.entity.ComponentEntity;
import de.bsnsoft.megarepo.database.repository.AssetJpaRepository;
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
 * Judges an upload into a hosted repository, the way the enforcement path judges
 * a download — and now, literally, with the same code.
 *
 * <h2>Why uploads are evaluated at all</h2>
 *
 * Phase 1 never looked at one, and the gap is not academic: the component a
 * developer publishes into a hosted repository is the component every consumer
 * of that repository then pulls, and a repository whose downloads are gated
 * while its uploads are not is a repository with an unlocked back door. The
 * customer asked for hosted uploads to be evaluated, and this is that
 * evaluation.
 *
 * <h2>What this class does, after osTicket #155155</h2>
 *
 * It answers one question the download path answers differently — <em>what
 * component is being written, and was this path already here?</em> — and hands
 * everything else to {@link FirewallDecisionAssembly}. The first version of this
 * class assembled its own verdict, and the predictable happened: it had no
 * exemption step, so an operator could approve an exemption, watch the component
 * download, and watch the identical publish be refused. Copying the exemption
 * lookup in here would have fixed the symptom and kept the cause — two decision
 * assemblies that only agree while somebody remembers to edit both.
 *
 * <p>So what is left here is the part that really is upload-shaped:
 *
 * <ul>
 *   <li>the identity comes from the {@link ComponentEntity} the format handler
 *       has just written, because the layout grammar for a publish lives in the
 *       format module and nowhere else;</li>
 *   <li>{@code upload=true} reaches the rules, so a rule that reads differently
 *       when something is being published can;</li>
 *   <li>and the grandfathering question is asked of the stored asset rather than
 *       of an inspection, since the asset is being written as we look at it.</li>
 * </ul>
 *
 * <h2>What this class is not</h2>
 *
 * It is <b>not wired into {@code RepositoryRouter}</b>, on purpose. This package
 * delivers the verdict; wiring the PUT path — deciding where in the upload
 * sequence it runs and what a refusal does to already-written bytes — belongs to
 * the enforcement-wiring package, which owns the router and the block response.
 * Everything here is a bean with a method and no side effect on the request
 * besides the verdict it returns and the queue entry a hold produces.
 *
 * <h2>When it declines to decide</h2>
 *
 * The same two switches as a download: the global enforcement switch, which
 * ships off, and the repository's mode, which has to be {@code QUARANTINE}.
 * Anything else returns {@link FirewallEvaluation.Outcome#NOT_ENFORCING} with no
 * query issued, so an installation that upgrades into this code cannot start
 * refusing a publish it accepted yesterday. A proxy or group repository is never
 * evaluated here either — nothing is published into one.
 *
 * <h2>Failure serves the publish</h2>
 *
 * Every path catches {@link RuntimeException} and answers "allow". A rule that
 * cannot be evaluated is {@code INDETERMINATE} and the repository's fail mode
 * decides; a defect in the firewall is not a verdict and must not cost a
 * developer their release.
 */
@Service
public class FirewallUploadEvaluator {

    private static final Logger log = LoggerFactory.getLogger(FirewallUploadEvaluator.class);

    private final FirewallEvaluationService evaluationService;
    private final FirewallEnforcementSettingsService enforcementSettings;
    private final FirewallDecisionAssembly assembly;
    private final AdvisoryLookupService advisories;
    private final AssetJpaRepository assets;
    private final PurlBuilder purlBuilder;

    public FirewallUploadEvaluator(
            FirewallEvaluationService evaluationService,
            FirewallEnforcementSettingsService enforcementSettings,
            FirewallDecisionAssembly assembly,
            AdvisoryLookupService advisories,
            AssetJpaRepository assets,
            PurlBuilder purlBuilder) {
        this.evaluationService = evaluationService;
        this.enforcementSettings = enforcementSettings;
        this.assembly = assembly;
        this.advisories = advisories;
        this.assets = assets;
        this.purlBuilder = purlBuilder;
    }

    /**
     * What is being published, as much of it as the caller can state.
     *
     * @param repositoryId the hosted repository being published into
     * @param repositoryName its name, for the audit trail and the 403 body
     * @param repositoryType its type; only {@link RepositoryType#HOSTED} is
     *     evaluated
     * @param path the artifact path being written
     * @param identity what the component is. The caller builds it — the format
     *     handler is the only thing that knows how to read coordinates out of an
     *     upload — or uses {@link #evaluate(UUID, String, RepositoryType, String,
     *     ComponentEntity, String, FirewallRequestContext)}, which derives it
     */
    public record UploadCandidate(
            UUID repositoryId,
            String repositoryName,
            RepositoryType repositoryType,
            String path,
            ComponentIdentity identity) {}

    /**
     * Decides whether one upload may be published.
     *
     * <p>Never throws.
     *
     * @param candidate what is being published
     * @param context who is publishing it
     * @return the evaluation. {@link FirewallEvaluation#blocked()} is the answer,
     *     and {@link FirewallEvaluation#decision()} carries the policy name and
     *     the matched rules the block response needs
     */
    public FirewallEvaluation evaluate(UploadCandidate candidate, FirewallRequestContext context) {
        FirewallRepositorySettings settings = FirewallRepositorySettings.fallback(FirewallMode.OFF);
        if (candidate == null) {
            return notEnforcing(null, null, null, settings);
        }
        try {
            if (candidate.repositoryId() == null
                    || candidate.repositoryType() != RepositoryType.HOSTED
                    || !enforcementSettings.enforcementEnabled()) {
                return notEnforcing(candidate.repositoryId(), candidate.repositoryName(),
                        candidate.path(), settings);
            }

            settings = evaluationService.resolveSettings(candidate.repositoryId());
            if (!settings.enforces()) {
                return notEnforcing(candidate.repositoryId(), candidate.repositoryName(),
                        candidate.path(), settings);
            }
            return assembly.decide(
                    inspect(candidate, settings), candidate.repositoryType(), true, context);

        } catch (RuntimeException e) {
            log.warn("Repository firewall upload evaluation failed for {}/{} — the upload was allowed",
                    candidate.repositoryName(), candidate.path(), e);
            return notEnforcing(candidate.repositoryId(), candidate.repositoryName(),
                    candidate.path(), settings)
                    .withOutcome(FirewallEvaluation.Outcome.FAILED);
        }
    }

    /**
     * The same, deriving the identity from a component row.
     *
     * <p>For a caller that has already built the {@link ComponentEntity} — the
     * format handlers do, as the last step before the asset is written.
     */
    public FirewallEvaluation evaluate(
            UUID repositoryId,
            String repositoryName,
            RepositoryType repositoryType,
            String path,
            ComponentEntity component,
            String sha256,
            FirewallRequestContext context) {

        ComponentIdentity identity;
        try {
            identity = component == null
                    ? new ComponentIdentity.Unidentified(null, null, null, null)
                    : purlBuilder.identify(component, sha256);
        } catch (RuntimeException e) {
            log.debug("Could not identify the uploaded component at {}/{}", repositoryName, path, e);
            identity = new ComponentIdentity.Unidentified(null, null, null, null);
        }
        return evaluate(
                new UploadCandidate(repositoryId, repositoryName, repositoryType, path, identity),
                context);
    }

    // ------------------------------------------------------------------

    /**
     * What is known about the component before anything is judged — the publish
     * side's answer to {@link FirewallEvaluationService#inspect}.
     *
     * <p>The two differ in where the identity comes from and in nothing else. A
     * download reads the stored asset and its component row; a publish is handed
     * the component the format handler extracted while writing, because that path
     * grammar lives in the format module. Everything downstream — advisories,
     * facts, rules, exemptions, fail mode, quarantine — sees the same shape either
     * way, which is the point.
     *
     * <p>{@code UNRESOLVABLE_IDENTITY} is not a reason to stop: a component whose
     * coordinates could not be built is exactly what {@code UNKNOWN_COMPONENT}
     * is about, and an armed repository should be able to refuse a publish it
     * cannot identify. Only the advisory lookup is skipped, because no feed
     * indexes a bare digest.
     */
    private FirewallEvaluation inspect(
            UploadCandidate candidate, FirewallRepositorySettings settings) {

        ComponentIdentity identity = candidate.identity();
        List<AdvisoryFinding> findings =
                identity.isResolvable() ? advisories.findAdvisories(identity) : List.of();

        FirewallEvaluation.Outcome outcome;
        if (!identity.isResolvable()) {
            outcome = FirewallEvaluation.Outcome.UNRESOLVABLE_IDENTITY;
        } else {
            outcome = findings.isEmpty()
                    ? FirewallEvaluation.Outcome.CLEAN
                    : FirewallEvaluation.Outcome.MATCHED;
        }

        return new FirewallEvaluation(
                candidate.repositoryId(), candidate.repositoryName(), candidate.path(),
                settings, identity, findings, outcome,
                isPreExisting(candidate), FirewallDecision.notEvaluated());
    }

    /**
     * Whether this path already holds an artifact stored before enforcement was
     * switched on.
     *
     * <p>One indexed read, and only on a repository that is actually being
     * enforced. The alternative — declaring every upload new by definition —
     * would make a re-publish of a long-standing artifact fail the first time an
     * operator arms the firewall, which is precisely the class of breakage the
     * watermark exists to prevent.
     *
     * <p>Read here rather than taken from
     * {@link FirewallEnforcementSettingsService#enforcingSince()} through the
     * download path's helper, because the two answer a subtly different question
     * about a missing row. A download that finds no asset is looking at something
     * that is not there and grandfathers it; a publish that finds no asset is
     * looking at coordinates that are genuinely new, and calling those
     * "pre-existing" would exempt every first publish from the policy — the
     * opposite of what arming the firewall is for.
     */
    private boolean isPreExisting(UploadCandidate candidate) {
        Instant watermark = enforcementSettings.enforcingSince();
        if (watermark == null || candidate.path() == null) {
            return false;
        }
        Optional<AssetEntity> asset =
                assets.findByRepositoryIdAndPath(candidate.repositoryId(), candidate.path());
        return asset.map(AssetEntity::getCreatedAt)
                .map(createdAt -> createdAt.isBefore(watermark))
                .orElse(false);
    }

    private static FirewallEvaluation notEnforcing(
            UUID repositoryId,
            String repositoryName,
            String path,
            FirewallRepositorySettings settings) {
        return new FirewallEvaluation(
                repositoryId, repositoryName, path, settings, null, List.of(),
                FirewallEvaluation.Outcome.NOT_ENFORCING, false, FirewallDecision.notEvaluated());
    }
}
