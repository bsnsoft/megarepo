package de.bsnsoft.megarepo.repository.advisory;

import com.github.packageurl.PackageURL;
import de.bsnsoft.megarepo.database.entity.AdvisoryAffectedEntity;
import de.bsnsoft.megarepo.database.entity.AdvisoryEntity;
import de.bsnsoft.megarepo.database.repository.AdvisoryAffectedJpaRepository;
import de.bsnsoft.megarepo.database.repository.AdvisoryJpaRepository;
import de.bsnsoft.megarepo.repository.firewall.identity.ComponentIdentity;
import de.bsnsoft.megarepo.repository.firewall.identity.VersionRange;
import de.bsnsoft.megarepo.repository.firewall.identity.VersionScheme;
import de.bsnsoft.megarepo.repository.firewall.identity.VersionSchemes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Answers "which advisories affect this component?" from the local advisory
 * store.
 *
 * <h2>Never the network</h2>
 *
 * This is the query that will sit on the download request path, and the
 * customer's requirement is explicit: a request thread must never wait on an
 * external service. Every advisory source therefore syncs in the background into
 * {@code advisory} / {@code advisory_affected}, and this class reads nothing
 * else. It holds no HTTP client and depends on no source. If a feed is stale or
 * a sync is failing, the answer here is the last known good data — never a
 * timeout.
 *
 * <h2>How a match is made</h2>
 *
 * <ol>
 *   <li><b>Exact pass.</b> {@code (purl_type, purl_namespace, purl_name)}
 *       against {@code idx_advisory_affected_purl}. Only advisories that
 *       published real purls — OSV, GHSA — can match here, and a match means
 *       every part of the identity agreed. Labelled
 *       {@link MatchConfidence#EXACT}.</li>
 *   <li><b>CPE-derived pass.</b> NVD names software by {@code vendor:product},
 *       which carries neither an ecosystem nor anything that maps to a purl
 *       namespace, so those rows live under the reserved purl type {@code cpe}
 *       and can only be matched by product name (backed by
 *       {@code idx_advisory_affected_purl_name}, V14). Labelled
 *       {@link MatchConfidence#HEURISTIC} — these are real CVEs matched on a
 *       weaker identity, and the label is what lets a policy or a report treat
 *       them accordingly instead of silently blocking on a name collision.</li>
 *   <li><b>Version filter.</b> Candidate rows are narrowed with
 *       {@link VersionScheme#contains}, using the ecosystem of the
 *       <em>queried</em> component. That is deliberate for the CPE-derived rows
 *       too: the version string being compared is the component's, so it obeys
 *       the component's ecosystem grammar, not the advisory's (a CPE has no
 *       ecosystem to obey).</li>
 *   <li><b>Merge.</b> {@link AdvisoryMergeService} collapses advisories that
 *       describe the same vulnerability, keeping every source and confidence
 *       label.</li>
 * </ol>
 *
 * <p>Withdrawn advisories are excluded: an upstream retraction has to clear a
 * previously flagged component, which is why {@code withdrawn_at} entries are
 * still ingested but never returned here.
 */
@Service
public class AdvisoryLookupService {

    private static final Logger log = LoggerFactory.getLogger(AdvisoryLookupService.class);

    private final AdvisoryAffectedJpaRepository affected;
    private final AdvisoryJpaRepository advisories;
    private final AdvisoryMergeService mergeService;

    public AdvisoryLookupService(
            AdvisoryAffectedJpaRepository affected,
            AdvisoryJpaRepository advisories,
            AdvisoryMergeService mergeService) {
        this.affected = affected;
        this.advisories = advisories;
        this.mergeService = mergeService;
    }

    /**
     * Advisories affecting the component behind an identity.
     *
     * <p>Only {@link ComponentIdentity.Purl} can be answered — a content hash
     * names a byte stream, and no advisory feed indexes those. Hash and
     * unidentified components yield an empty list here and are the policy
     * engine's problem, not this class's.
     */
    @Transactional(readOnly = true)
    public List<AdvisoryFinding> findAdvisories(ComponentIdentity identity) {
        if (identity instanceof ComponentIdentity.Purl purlIdentity) {
            return findAdvisories(purlIdentity.purl());
        }
        return List.of();
    }

    /** Advisories affecting the given package version. */
    @Transactional(readOnly = true)
    public List<AdvisoryFinding> findAdvisories(PackageURL purl) {
        if (purl == null) {
            return List.of();
        }
        return findAdvisories(
                purl.getType(), purl.getNamespace(), purl.getName(), purl.getVersion(), true);
    }

    /** Advisories affecting the given coordinates, CPE-derived matches included. */
    @Transactional(readOnly = true)
    public List<AdvisoryFinding> findAdvisories(
            String purlType, String purlNamespace, String purlName, String version) {
        return findAdvisories(purlType, purlNamespace, purlName, version, true);
    }

    /**
     * Advisories affecting the given coordinates.
     *
     * @param version the component's version. When it is absent, ranges cannot
     *     be evaluated at all, so only advisories that affect <em>every</em>
     *     version of the package are returned — the subset that is true
     *     regardless of the version. Returning all candidate rows instead would
     *     flag every release a package ever had.
     * @param includeCpeDerived whether to run the CPE-derived pass. Off gives
     *     the purl-native answer alone, which is what a source-comparison report
     *     needs on one side of the diff.
     */
    @Transactional(readOnly = true)
    public List<AdvisoryFinding> findAdvisories(
            String purlType,
            String purlNamespace,
            String purlName,
            String version,
            boolean includeCpeDerived) {

        String type = trimToNull(purlType);
        String name = trimToNull(purlName);
        if (type == null || name == null) {
            return List.of();
        }
        type = type.toLowerCase(Locale.ROOT);

        VersionScheme scheme = VersionSchemes.forPurlType(type);
        Map<String, List<Candidate>> matchesByAdvisory = new LinkedHashMap<>();

        collect(
                affected.findByPurlCoordinates(type, trimToNull(purlNamespace), name),
                scheme,
                version,
                matchesByAdvisory);

        if (includeCpeDerived && !CpePurlTranslator.isCpeDerived(type)) {
            Set<String> products = CpePurlTranslator.productCandidatesFor(name);
            if (!products.isEmpty()) {
                collect(
                        affected.findByPurlTypeAndPurlNameIn(CpePurlTranslator.PURL_TYPE, products),
                        scheme,
                        version,
                        matchesByAdvisory);
            }
        }

        if (matchesByAdvisory.isEmpty()) {
            return List.of();
        }

        List<AdvisoryEntity> rows =
                advisories.findByIdInAndWithdrawnAtIsNull(matchesByAdvisory.keySet());
        List<AdvisoryFinding> unmerged = new ArrayList<>(rows.size());
        for (AdvisoryEntity row : rows) {
            unmerged.add(toFinding(row, matchesByAdvisory.get(row.getId())));
        }

        List<AdvisoryFinding> findings = mergeService.merge(unmerged);
        log.debug("Advisory lookup {}/{}/{}@{}: {} candidate advisories → {} findings",
                type, purlNamespace, name, version, matchesByAdvisory.size(), findings.size());
        return findings;
    }

    private static void collect(
            List<AdvisoryAffectedEntity> rows,
            VersionScheme scheme,
            String version,
            Map<String, List<Candidate>> target) {

        String componentVersion = trimToNull(version);
        for (AdvisoryAffectedEntity row : rows) {
            VersionRange range =
                    new VersionRange(row.getIntroduced(), row.getFixed(), row.getLastAffected());

            boolean applies = componentVersion == null
                    ? range.isUnbounded()
                    : scheme.contains(range, componentVersion);
            if (!applies) {
                continue;
            }

            target.computeIfAbsent(row.getAdvisoryId(), key -> new ArrayList<>())
                    .add(new Candidate(
                            CpePurlTranslator.confidenceFor(row.getPurlType()), describeRange(row)));
        }
    }

    private static AdvisoryFinding toFinding(AdvisoryEntity advisory, List<Candidate> candidates) {
        List<AdvisoryMatch> matches = new ArrayList<>(candidates.size());
        for (Candidate candidate : candidates) {
            matches.add(new AdvisoryMatch(
                    advisory.getId(),
                    advisory.getSource(),
                    candidate.confidence(),
                    candidate.matchedRange()));
        }
        return new AdvisoryFinding(
                advisory.getId(),
                advisory.getSummary(),
                advisory.getSeverity(),
                advisory.getCvssScore(),
                advisory.getCvssVector(),
                advisory.getPublished(),
                advisory.getModified(),
                matches);
    }

    /**
     * A matched affected row before its advisory has been loaded. The source
     * label lives on {@code advisory}, not on {@code advisory_affected}, so it
     * can only be attached once both halves are in hand.
     */
    private record Candidate(MatchConfidence confidence, String matchedRange) {}

    /**
     * The evidence string carried into the finding: the range as the source
     * published it when there is one, otherwise the resolved bounds.
     */
    private static String describeRange(AdvisoryAffectedEntity candidate) {
        String published = trimToNull(candidate.getVersionRange());
        if (published != null) {
            return published;
        }
        String introduced = candidate.getIntroduced() == null ? "*" : candidate.getIntroduced();
        if (candidate.getFixed() != null) {
            return "[" + introduced + ", " + candidate.getFixed() + ")";
        }
        if (candidate.getLastAffected() != null) {
            return "[" + introduced + ", " + candidate.getLastAffected() + "]";
        }
        return "[" + introduced + ", *)";
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
