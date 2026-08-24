package de.bsnsoft.megarepo.repository.firewall.report;

import com.github.packageurl.PackageURL;
import de.bsnsoft.megarepo.database.entity.AdvisoryAffectedEntity;
import de.bsnsoft.megarepo.database.entity.AdvisoryEntity;
import de.bsnsoft.megarepo.database.repository.AdvisoryAffectedJpaRepository;
import de.bsnsoft.megarepo.database.repository.AdvisoryJpaRepository;
import de.bsnsoft.megarepo.repository.advisory.AdvisoryFinding;
import de.bsnsoft.megarepo.repository.advisory.AdvisoryLookupService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The purl side of the diff, in three parts.
 *
 * <ol>
 *   <li><b>The purl-native answer.</b> {@link AdvisoryLookupService} with the
 *       CPE-derived pass switched off. This is what the comparison treats as
 *       "purl matching found it": every part of the identity agreed and the
 *       finding is {@code EXACT}. Anything weaker would be comparing CPE
 *       guessing against CPE guessing.</li>
 *   <li><b>The full answer.</b> The same lookup with the CPE-derived pass on —
 *       what the firewall will actually evaluate. The extra ids are the ones the
 *       replacement still reports, but labelled {@code HEURISTIC}. Recording
 *       them is what keeps the report from claiming a false positive was removed
 *       when it was only downgraded.</li>
 *   <li><b>The identity match without the version filter.</b> A direct read of
 *       {@code advisory_affected} on the purl coordinates, withdrawn advisories
 *       excluded. This is the only way to tell "purl matching says this is a
 *       different package" apart from "purl matching says this version is
 *       patched", and those two are different claims with different evidence.</li>
 * </ol>
 *
 * <p>The first two go through the real lookup service rather than a
 * reimplementation, so the report measures the shipping behaviour including its
 * merge and withdrawn-advisory rules.
 *
 * <p>Read-only. Every method here is a query.
 */
@Component
@Transactional(readOnly = true)
public class PurlAdvisoryProbe {

    private final AdvisoryLookupService lookup;
    private final AdvisoryAffectedJpaRepository affected;
    private final AdvisoryJpaRepository advisories;

    public PurlAdvisoryProbe(
            AdvisoryLookupService lookup,
            AdvisoryAffectedJpaRepository affected,
            AdvisoryJpaRepository advisories) {
        this.lookup = lookup;
        this.affected = affected;
        this.advisories = advisories;
    }

    /**
     * What purl matching did for one component.
     *
     * @param findings the purl-native findings, merged and ordered as the
     *     firewall would see them
     * @param heuristicOnlyIds advisory ids that only the CPE-derived pass adds
     * @param matchedIgnoringVersion advisory id → the published affected range,
     *     for rows whose purl coordinates match regardless of the version
     */
    public record Result(
            List<AdvisoryFinding> findings,
            Set<String> heuristicOnlyIds,
            Map<String, String> matchedIgnoringVersion) {

        public Result {
            findings = findings == null ? List.of() : List.copyOf(findings);
            heuristicOnlyIds = heuristicOnlyIds == null ? Set.of() : Set.copyOf(heuristicOnlyIds);
            matchedIgnoringVersion =
                    matchedIgnoringVersion == null ? Map.of() : Map.copyOf(matchedIgnoringVersion);
        }

        static Result empty() {
            return new Result(List.of(), Set.of(), Map.of());
        }

        /**
         * Every upstream id the purl-native findings carry, merged aliases
         * included. A finding that merged {@code GHSA-x} and {@code CVE-y}
         * contributes both, so a legacy hit on {@code CVE-y} is recognised as the
         * same vulnerability rather than counted as a false positive.
         */
        public Set<String> purlNativeIds() {
            Set<String> ids = new LinkedHashSet<>();
            for (AdvisoryFinding finding : findings) {
                ids.addAll(finding.advisoryIds());
            }
            return ids;
        }
    }

    /** Probes the advisory store for one purl. */
    public Result probe(PackageURL purl) {
        if (purl == null) {
            return Result.empty();
        }
        String type = purl.getType();
        String namespace = purl.getNamespace();
        String name = purl.getName();
        String version = purl.getVersion();

        List<AdvisoryFinding> purlNative =
                lookup.findAdvisories(type, namespace, name, version, false);
        List<AdvisoryFinding> withCpeDerived =
                lookup.findAdvisories(type, namespace, name, version, true);

        Set<String> heuristicOnly = new LinkedHashSet<>(idsOf(withCpeDerived));
        heuristicOnly.removeAll(idsOf(purlNative));

        return new Result(purlNative, heuristicOnly, identityMatches(type, namespace, name));
    }

    /**
     * Advisory rows whose purl coordinates match the component, with the version
     * filter deliberately not applied.
     *
     * <p>Withdrawn advisories are filtered out here as well. Without that, a
     * retracted advisory would make a component look like a version
     * disagreement when in truth neither side should report it.
     */
    private Map<String, String> identityMatches(String type, String namespace, String name) {
        List<AdvisoryAffectedEntity> rows = affected.findByPurlCoordinates(type, namespace, name);
        if (rows.isEmpty()) {
            return Map.of();
        }

        Map<String, String> rangeByAdvisory = new LinkedHashMap<>();
        for (AdvisoryAffectedEntity row : rows) {
            rangeByAdvisory.putIfAbsent(row.getAdvisoryId(), describe(row));
        }

        Set<String> live = new LinkedHashSet<>();
        for (AdvisoryEntity advisory :
                advisories.findByIdInAndWithdrawnAtIsNull(rangeByAdvisory.keySet())) {
            live.add(advisory.getId());
        }
        rangeByAdvisory.keySet().retainAll(live);
        return rangeByAdvisory;
    }

    private static Set<String> idsOf(List<AdvisoryFinding> findings) {
        Set<String> ids = new LinkedHashSet<>();
        for (AdvisoryFinding finding : findings) {
            ids.addAll(finding.advisoryIds());
        }
        return ids;
    }

    /** The affected range as the source published it, or the resolved bounds. */
    static String describe(AdvisoryAffectedEntity row) {
        String published = row.getVersionRange();
        if (published != null && !published.isBlank()) {
            return published.trim();
        }
        String introduced = row.getIntroduced() == null ? "*" : row.getIntroduced();
        if (row.getFixed() != null) {
            return "[" + introduced + ", " + row.getFixed() + ")";
        }
        if (row.getLastAffected() != null) {
            return "[" + introduced + ", " + row.getLastAffected() + "]";
        }
        return "[" + introduced + ", *)";
    }
}
