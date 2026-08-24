package de.bsnsoft.megarepo.repository.firewall.report;

import de.bsnsoft.megarepo.database.entity.CveAffectedProductEntity;
import de.bsnsoft.megarepo.database.repository.CveAffectedProductJpaRepository;
import de.bsnsoft.megarepo.repository.nvd.NvdCveLookupService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The legacy side of the diff: what {@link NvdCveLookupService} matches, plus
 * the evidence it does not return.
 *
 * <h2>Why this calls the legacy code instead of re-implementing it</h2>
 *
 * The whole point of the report is to measure the CPE guessing that ships today.
 * A copy of {@code buildProductCandidates} inside the report would measure the
 * copy, and every divergence between the two would silently become a reported
 * improvement. So the candidate generation and the version predicate are taken
 * from {@link NvdCveLookupService} itself — the report is wrong exactly when the
 * shipping code is wrong, which is the only relationship that makes the numbers
 * mean anything.
 *
 * <p>What is <em>not</em> reused is
 * {@link NvdCveLookupService#findApplicableCves(String, String, String, String)}:
 * it returns CVE ids and scores but drops the matched
 * {@link CveAffectedProductEntity} rows, and those rows are the evidence — which
 * generated product candidate hit, which CPE vendor it belonged to, which
 * version bounds applied. A false-positive claim without them is an assertion.
 * The rows are re-derived here from the same query and filtered with the same
 * predicate, so the applicable set is identical to what the service returns.
 *
 * <h2>Batching</h2>
 *
 * {@link #probeAll(List)} unions the product candidates of a whole page of
 * components into a handful of {@code IN} queries instead of one query per
 * component. On a repository where thousands of artifacts share a base name that
 * is the difference between a report that finishes and one that does not.
 *
 * <p>Read-only, in short transactions: one per chunk, never one for the run.
 */
@Component
@Transactional(readOnly = true)
public class LegacyCpeProbe {

    /**
     * Values per {@code IN} list. PostgreSQL copes with far more, but a bounded
     * chunk keeps each statement's plan and each transaction short.
     */
    private static final int CHUNK = 1_000;

    private final CveAffectedProductJpaRepository affectedProducts;

    public LegacyCpeProbe(CveAffectedProductJpaRepository affectedProducts) {
        this.affectedProducts = affectedProducts;
    }

    /**
     * What the legacy lookup did for one component.
     *
     * @param productCandidates the CPE product spellings the legacy code
     *     generated from the artifact name — the guess itself, reported so the
     *     reader can see it
     * @param matchedIgnoringVersion CVE id → the CPE row that matched by product
     *     name, version bounds not yet applied. This is what makes a version
     *     disagreement distinguishable from an identity disagreement.
     * @param applicable the CVE ids the legacy lookup would actually report,
     *     i.e. after {@code NvdCveLookupService.versionApplies}
     */
    public record Result(
            Set<String> productCandidates,
            Map<String, CveAffectedProductEntity> matchedIgnoringVersion,
            Set<String> applicable) {

        static Result empty() {
            return new Result(Set.of(), Map.of(), Set.of());
        }
    }

    /** One component. Prefer {@link #probeAll(List)} for more than a handful. */
    public Result probe(String name, String version) {
        return probeAll(List.of(new Query(name, version))).get(0);
    }

    /** The coordinates the legacy lookup reads: artifact name and version. */
    public record Query(String name, String version) {}

    /**
     * A whole batch, in the order given.
     *
     * <p>The union of all candidate products is queried once (chunked), then the
     * resulting rows are handed back out per component. Components whose name is
     * null or blank yield {@link Result#empty()} — the legacy lookup returns an
     * empty list for those too.
     */
    public List<Result> probeAll(List<Query> queries) {
        List<Set<String>> candidatesPerQuery = new ArrayList<>(queries.size());
        Set<String> union = new LinkedHashSet<>();
        for (Query query : queries) {
            Set<String> candidates = query.name() == null || query.name().isBlank()
                    ? Set.of()
                    : NvdCveLookupService.buildProductCandidates(query.name());
            candidatesPerQuery.add(candidates);
            union.addAll(candidates);
        }

        Map<String, List<CveAffectedProductEntity>> rowsByProduct = loadRowsByProduct(union);

        List<Result> results = new ArrayList<>(queries.size());
        for (int i = 0; i < queries.size(); i++) {
            results.add(assemble(queries.get(i), candidatesPerQuery.get(i), rowsByProduct));
        }
        return results;
    }

    private Result assemble(
            Query query,
            Set<String> candidates,
            Map<String, List<CveAffectedProductEntity>> rowsByProduct) {

        if (candidates.isEmpty() || query.version() == null) {
            // NvdCveLookupService.findApplicableCves bails out on a null version
            // and on an empty candidate set; mirror both so the two sides of the
            // comparison see the same behaviour.
            return Result.empty();
        }

        Map<String, CveAffectedProductEntity> matched = new LinkedHashMap<>();
        Set<String> applicable = new LinkedHashSet<>();
        for (String candidate : candidates) {
            for (CveAffectedProductEntity row : rowsByProduct.getOrDefault(candidate, List.of())) {
                // The first row wins as the evidence for a CVE: candidates are
                // generated most-specific-first, so this is the closest match
                // the legacy path had.
                matched.putIfAbsent(row.getCveId(), row);
                if (NvdCveLookupService.versionApplies(row, query.version())) {
                    applicable.add(row.getCveId());
                }
            }
        }
        return new Result(candidates, matched, applicable);
    }

    private Map<String, List<CveAffectedProductEntity>> loadRowsByProduct(Set<String> products) {
        Map<String, List<CveAffectedProductEntity>> byProduct = new LinkedHashMap<>();
        for (List<String> chunk : chunks(products)) {
            for (CveAffectedProductEntity row : affectedProducts.findByProductIn(chunk)) {
                byProduct.computeIfAbsent(row.getProduct(), key -> new ArrayList<>()).add(row);
            }
        }
        return byProduct;
    }

    private static List<List<String>> chunks(Collection<String> values) {
        List<List<String>> chunks = new ArrayList<>();
        List<String> current = new ArrayList<>(Math.min(CHUNK, values.size()));
        for (String value : values) {
            current.add(value);
            if (current.size() == CHUNK) {
                chunks.add(current);
                current = new ArrayList<>(CHUNK);
            }
        }
        if (!current.isEmpty()) {
            chunks.add(current);
        }
        return chunks;
    }

    /** The CPE row rendered the way the legacy mirror stored it. */
    public static String describe(CveAffectedProductEntity row) {
        StringBuilder out = new StringBuilder("cpe ");
        out.append(row.getVendor() == null ? "*" : row.getVendor())
                .append(':')
                .append(row.getProduct());

        if (row.getVersionExact() != null) {
            return out.append(" =").append(row.getVersionExact()).toString();
        }

        StringBuilder bounds = new StringBuilder();
        appendBound(bounds, ">=", row.getVersionStartIncluding());
        appendBound(bounds, ">", row.getVersionStartExcluding());
        appendBound(bounds, "<", row.getVersionEndExcluding());
        appendBound(bounds, "<=", row.getVersionEndIncluding());
        return out.append(' ').append(bounds.isEmpty() ? "*" : bounds).toString();
    }

    private static void appendBound(StringBuilder out, String operator, String value) {
        if (value == null) {
            return;
        }
        if (!out.isEmpty()) {
            out.append(", ");
        }
        out.append(operator).append(value);
    }
}
