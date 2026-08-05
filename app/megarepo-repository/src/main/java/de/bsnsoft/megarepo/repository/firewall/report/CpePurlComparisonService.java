package de.bsnsoft.megarepo.repository.firewall.report;

import com.github.packageurl.PackageURL;
import de.bsnsoft.megarepo.database.entity.ComponentEntity;
import de.bsnsoft.megarepo.database.entity.CveAffectedProductEntity;
import de.bsnsoft.megarepo.database.entity.CveEntryEntity;
import de.bsnsoft.megarepo.database.repository.AdvisoryAffectedJpaRepository;
import de.bsnsoft.megarepo.database.repository.AdvisoryJpaRepository;
import de.bsnsoft.megarepo.database.repository.ComponentJpaRepository;
import de.bsnsoft.megarepo.database.repository.CveAffectedProductJpaRepository;
import de.bsnsoft.megarepo.database.repository.CveEntryJpaRepository;
import de.bsnsoft.megarepo.repository.advisory.AdvisoryFinding;
import de.bsnsoft.megarepo.repository.advisory.CpePurlTranslator;
import de.bsnsoft.megarepo.repository.firewall.identity.ComponentIdentity;
import de.bsnsoft.megarepo.repository.firewall.identity.PurlBuilder;
import de.bsnsoft.megarepo.repository.firewall.identity.VersionSchemes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Runs both vulnerability matching methods over the stored components and
 * writes down where they disagree.
 *
 * <h2>What this is for</h2>
 *
 * Phase 1 of the repository firewall replaces CPE product guessing with purl
 * identity. The customer approved the phase on one condition: send the
 * comparison report before anything starts enforcing. This service is that
 * report — the improvement is measured over the customer's own repository data
 * rather than argued from a design document.
 *
 * <p>It is therefore written to be disbelieved. Aggregates are always paired
 * with worked examples; the two sides are computed by calling the shipping
 * services rather than by re-implementing them; cases where purl matching is
 * <em>worse</em> ({@link DeltaKind#VERSION_ONLY_PURL} is a vulnerability the
 * legacy path also had to report, {@link DeltaKind#CPE_ONLY} entries flagged
 * {@code alsoReportedAsHeuristic} are downgrades and not removals) are counted
 * and rendered like every other case.
 *
 * <h2>Read-only, and structurally so</h2>
 *
 * Nothing here writes. Not by convention: this service calls only query methods,
 * the two probes are annotated {@code @Transactional(readOnly = true)}, and the
 * component pages are read through Spring Data's own read-only transactions.
 * There is deliberately <em>no</em> transaction spanning the whole run — a scan
 * over a large instance would otherwise hold one open for minutes and block
 * autovacuum on {@code components}.
 *
 * <p>It also never touches the network. Both methods answer from local tables:
 * {@code cve_affected_products} for the legacy path,
 * {@code advisory}/{@code advisory_affected} for the new one.
 *
 * <h2>Cost</h2>
 *
 * Per page of components: a handful of {@code IN} queries for the legacy side
 * (see {@link LegacyCpeProbe}), and three indexed queries per identified
 * component for the purl side. That asymmetry is intrinsic — advisory lookup is
 * keyed on the full purl, which has no batched equivalent — and is why the run
 * is bounded by {@link ComparisonReportRequest#maxComponents()} and paged.
 */
@Service
public class CpePurlComparisonService {

    private static final Logger log = LoggerFactory.getLogger(CpePurlComparisonService.class);

    private final ComponentJpaRepository components;
    private final PurlBuilder purlBuilder;
    private final LegacyCpeProbe legacyProbe;
    private final PurlAdvisoryProbe purlProbe;
    private final CveEntryJpaRepository cveEntries;
    private final CveAffectedProductJpaRepository cveAffectedProducts;
    private final AdvisoryJpaRepository advisories;
    private final AdvisoryAffectedJpaRepository advisoryAffected;

    public CpePurlComparisonService(
            ComponentJpaRepository components,
            PurlBuilder purlBuilder,
            LegacyCpeProbe legacyProbe,
            PurlAdvisoryProbe purlProbe,
            CveEntryJpaRepository cveEntries,
            CveAffectedProductJpaRepository cveAffectedProducts,
            AdvisoryJpaRepository advisories,
            AdvisoryAffectedJpaRepository advisoryAffected) {
        this.components = components;
        this.purlBuilder = purlBuilder;
        this.legacyProbe = legacyProbe;
        this.purlProbe = purlProbe;
        this.cveEntries = cveEntries;
        this.cveAffectedProducts = cveAffectedProducts;
        this.advisories = advisories;
        this.advisoryAffected = advisoryAffected;
    }

    /** The whole instance, with the default limits. */
    public CpePurlComparisonReport run() {
        return run(ComparisonReportRequest.defaults());
    }

    /** Runs the comparison and returns the report. Writes nothing. */
    public CpePurlComparisonReport run(ComparisonReportRequest request) {
        ComparisonReportRequest effective =
                request == null ? ComparisonReportRequest.defaults() : request;
        Instant startedAt = Instant.now();
        long startedNanos = System.nanoTime();

        AdvisoryStoreState storeState = readStoreState();
        Accumulator accumulator = new Accumulator(effective);

        boolean truncated = false;
        int pageSize = effective.pageSize();
        int pageIndex = 0;
        long scanned = 0;
        while (scanned < effective.maxComponents()) {
            List<ComponentEntity> page = loadPage(effective, pageIndex, pageSize);
            if (page.isEmpty()) {
                break;
            }
            boolean lastPage = page.size() < pageSize;

            long remaining = effective.maxComponents() - scanned;
            if (page.size() > remaining) {
                page = page.subList(0, (int) remaining);
                truncated = true;
            }

            compare(page, accumulator);
            scanned += page.size();
            pageIndex++;

            if (lastPage) {
                break;
            }
            if (scanned >= effective.maxComponents()) {
                truncated = true;
                break;
            }
        }

        Duration duration = Duration.ofNanos(System.nanoTime() - startedNanos);
        ComparisonSummary summary = accumulator.summarise(storeState);
        log.info(
                "CPE/purl comparison over {} components in {} ms: {} legacy findings, {} purl findings,"
                        + " {} legacy-only, {} purl-only, {} version disagreements",
                summary.componentsScanned(),
                duration.toMillis(),
                summary.legacyFindingsTotal(),
                summary.purlFindingsTotal(),
                summary.findingsCpeOnly(),
                summary.findingsPurlOnly(),
                summary.findingsVersionOnlyCpe() + summary.findingsVersionOnlyPurl());

        return new CpePurlComparisonReport(
                startedAt,
                duration,
                effective,
                effective.synthetic(),
                truncated,
                summary,
                accumulator.samples(),
                notes(summary, effective, truncated));
    }

    private List<ComponentEntity> loadPage(
            ComparisonReportRequest request, int pageIndex, int pageSize) {
        // Sorted by primary key so paging is stable even if components are being
        // written while the report runs.
        Pageable pageable = PageRequest.of(pageIndex, pageSize, Sort.by(Sort.Direction.ASC, "id"));
        return request.repositoryIds().isEmpty()
                ? components.findAllByIdNotNull(pageable)
                : components.findAllByRepositoryIdIn(request.repositoryIds(), pageable);
    }

    private void compare(List<ComponentEntity> batch, Accumulator accumulator) {
        List<LegacyCpeProbe.Query> queries = new ArrayList<>(batch.size());
        for (ComponentEntity component : batch) {
            queries.add(new LegacyCpeProbe.Query(component.getName(), component.getVersion()));
        }
        List<LegacyCpeProbe.Result> legacyResults = legacyProbe.probeAll(queries);

        for (int i = 0; i < batch.size(); i++) {
            accumulator.add(compareOne(batch.get(i), legacyResults.get(i)));
        }
    }

    private ComponentComparison compareOne(
            ComponentEntity component, LegacyCpeProbe.Result legacy) {

        ComponentIdentity identity = purlBuilder.identify(component);
        PackageURL purl =
                identity instanceof ComponentIdentity.Purl resolved ? resolved.purl() : null;
        PurlAdvisoryProbe.Result purlSide =
                purl == null ? PurlAdvisoryProbe.Result.empty() : purlProbe.probe(purl);

        List<VulnerabilityDelta> deltas =
                buildDeltas(component, purl, legacy, purlSide);

        ComparisonVerdict verdict;
        if (purl == null) {
            verdict = ComparisonVerdict.UNIDENTIFIED;
        } else if (deltas.isEmpty()) {
            verdict = ComparisonVerdict.BOTH_CLEAN;
        } else if (deltas.stream().allMatch(delta -> delta.kind().isAgreement())) {
            verdict = ComparisonVerdict.AGREEMENT;
        } else {
            verdict = ComparisonVerdict.DIVERGENT;
        }

        return new ComponentComparison(
                component.getId(),
                component.getRepositoryId(),
                component.getFormat(),
                component.getNamespace(),
                component.getName(),
                component.getVersion(),
                identity.key(),
                purl != null,
                verdict,
                deltas);
    }

    /**
     * The heart of the comparison.
     *
     * <p>A vulnerability is counted once. The purl side owns the identity of a
     * vulnerability it reports, because its findings carry every alias the merge
     * collapsed; a legacy CVE id becomes its own entry only when no purl finding
     * claims it. Without that rule a single Log4Shell reported as {@code GHSA-…}
     * by GitHub and as {@code CVE-…} by NVD would appear as one agreement and
     * one false positive.
     */
    private List<VulnerabilityDelta> buildDeltas(
            ComponentEntity component,
            PackageURL purl,
            LegacyCpeProbe.Result legacy,
            PurlAdvisoryProbe.Result purlSide) {

        Set<String> legacyApplicable = legacy.applicable();
        List<VulnerabilityDelta> deltas = new ArrayList<>();
        Set<String> claimedByPurl = new LinkedHashSet<>();

        for (AdvisoryFinding finding : purlSide.findings()) {
            Set<String> overlap = new LinkedHashSet<>(finding.advisoryIds());
            overlap.retainAll(legacyApplicable);
            claimedByPurl.addAll(overlap);

            String purlEvidence = purlEvidence(purl, finding);
            if (!overlap.isEmpty()) {
                CveAffectedProductEntity legacyRow =
                        firstLegacyRow(legacy, overlap);
                deltas.add(new VulnerabilityDelta(
                        finding.advisoryId(),
                        DeltaKind.AGREED,
                        finding.severity(),
                        finding.cvssScore(),
                        legacyRow == null ? null : LegacyCpeProbe.describe(legacyRow),
                        purlEvidence,
                        "Both methods report this vulnerability for the same component.",
                        false));
                continue;
            }

            boolean legacyKnowsThePackage = finding.advisoryIds().stream()
                    .anyMatch(legacy.matchedIgnoringVersion()::containsKey);
            if (legacyKnowsThePackage) {
                CveAffectedProductEntity legacyRow = firstLegacyRow(
                        legacy, finding.advisoryIds());
                deltas.add(new VulnerabilityDelta(
                        finding.advisoryId(),
                        DeltaKind.VERSION_ONLY_PURL,
                        finding.severity(),
                        finding.cvssScore(),
                        legacyRow == null ? null : LegacyCpeProbe.describe(legacyRow),
                        purlEvidence,
                        versionDisagreementAssessment(component, purl, false),
                        false));
            } else {
                deltas.add(new VulnerabilityDelta(
                        finding.advisoryId(),
                        DeltaKind.PURL_ONLY,
                        finding.severity(),
                        finding.cvssScore(),
                        null,
                        purlEvidence,
                        missedByLegacyAssessment(component, legacy),
                        false));
            }
        }

        Set<String> unclaimed = new LinkedHashSet<>(legacyApplicable);
        unclaimed.removeAll(claimedByPurl);
        Map<String, CveEntryEntity> cveMetadata = loadCveMetadata(unclaimed);

        for (String cveId : unclaimed) {
            CveAffectedProductEntity row = legacy.matchedIgnoringVersion().get(cveId);
            String cpeEvidence = row == null ? null : LegacyCpeProbe.describe(row);
            String publishedRange = purlSide.matchedIgnoringVersion().get(cveId);
            boolean heuristic = purlSide.heuristicOnlyIds().contains(cveId);
            CveEntryEntity entry = cveMetadata.get(cveId);

            if (publishedRange != null) {
                deltas.add(new VulnerabilityDelta(
                        cveId,
                        DeltaKind.VERSION_ONLY_CPE,
                        entry == null ? null : entry.getSeverity(),
                        entry == null ? null : entry.getCvssScore(),
                        cpeEvidence,
                        purlRangeEvidence(purl, publishedRange),
                        versionDisagreementAssessment(component, purl, true),
                        heuristic));
            } else {
                deltas.add(new VulnerabilityDelta(
                        cveId,
                        DeltaKind.CPE_ONLY,
                        entry == null ? null : entry.getSeverity(),
                        entry == null ? null : entry.getCvssScore(),
                        cpeEvidence,
                        null,
                        falsePositiveAssessment(
                                component,
                                purl,
                                row,
                                heuristic,
                                !purlSide.matchedIgnoringVersion().isEmpty()),
                        heuristic));
            }
        }

        return deltas;
    }

    /**
     * Severity and score for the legacy-only CVEs of one component, in one
     * query. The purl side carries its own metadata on the finding; the legacy
     * path returns bare ids from {@code cve_affected_products}, so the CVE rows
     * have to be read separately.
     */
    private Map<String, CveEntryEntity> loadCveMetadata(Set<String> cveIds) {
        if (cveIds.isEmpty()) {
            return Map.of();
        }
        Map<String, CveEntryEntity> byId = new LinkedHashMap<>();
        for (CveEntryEntity entry : cveEntries.findAllById(cveIds)) {
            byId.put(entry.getCveId(), entry);
        }
        return byId;
    }

    private static CveAffectedProductEntity firstLegacyRow(
            LegacyCpeProbe.Result legacy, Set<String> ids) {
        for (String id : ids) {
            CveAffectedProductEntity row = legacy.matchedIgnoringVersion().get(id);
            if (row != null) {
                return row;
            }
        }
        return null;
    }

    private static String purlEvidence(PackageURL purl, AdvisoryFinding finding) {
        String range = finding.matches().isEmpty() ? null : finding.matches().get(0).matchedRange();
        return coordinatesOf(purl) + (range == null ? "" : " affected " + range);
    }

    private static String purlRangeEvidence(PackageURL purl, String publishedRange) {
        return coordinatesOf(purl) + " affected " + publishedRange;
    }

    private static String coordinatesOf(PackageURL purl) {
        return purl == null ? "no purl" : purl.getCoordinates();
    }

    /**
     * Why a legacy-only report is suspected to be a false positive.
     *
     * <p>Derived from the data in each case, never a fixed sentence: the two
     * defects the design names produce different evidence, and a reader must be
     * able to tell which one they are looking at.
     */
    private static String falsePositiveAssessment(
            ComponentEntity component,
            PackageURL purl,
            CveAffectedProductEntity row,
            boolean stillHeuristic,
            boolean purlKnowsThePackage) {

        String suffix = stillHeuristic
                ? " The replacement still surfaces it through the CPE-derived pass, labelled"
                        + " HEURISTIC — downgraded for review, not removed."
                : "";

        if (purl == null) {
            return "The component has no package identity ("
                    + component.getFormat()
                    + " carries no coordinates), so the legacy match rests on the file name alone"
                    + " and cannot be confirmed by any advisory feed. The replacement reports"
                    + " nothing here at all: read this as a loss of coverage, not as a proven"
                    + " false positive."
                    + suffix;
        }
        if (row == null) {
            return "The legacy path reported this CVE but the matching CPE row could not be"
                    + " re-read; treat as unexplained."
                    + suffix;
        }

        // When the advisory store carries other advisories for exactly this
        // purl, "purl matching found nothing" is a claim about the id, not
        // about the package — and Phase 1 has no alias table, so the same
        // vulnerability under a GHSA id looks like a disagreement. Saying so is
        // the difference between a report and a sales sheet.
        if (purlKnowsThePackage) {
            return "purl matching does know this package — it reports other advisories for the"
                    + " same purl — but no advisory in the store names "
                    + row.getCveId()
                    + " against it. Either the CPE match (product '"
                    + row.getProduct()
                    + "', vendor '"
                    + (row.getVendor() == null ? "*" : row.getVendor())
                    + "') is wrong, or the same vulnerability is published under another id that"
                    + " Phase 1 does not yet alias. Check this case by hand."
                    + suffix;
        }

        String product = row.getProduct();
        String name = component.getName() == null ? "" : component.getName();
        boolean nameFolded = !product.equalsIgnoreCase(name)
                && !product.equalsIgnoreCase(name.replace('-', '_'))
                && !product.equalsIgnoreCase(name.replace('.', '_'));

        if (nameFolded) {
            return "The legacy candidate generation folded the artifact name '"
                    + name
                    + "' onto the coarser CPE product '"
                    + product
                    + "'. Those are separately released artifacts, so a CVE filed against the"
                    + " product does not follow to this one."
                    + suffix;
        }
        return "The CPE product '"
                + product
                + "' belongs to vendor '"
                + (row.getVendor() == null ? "*" : row.getVendor())
                + "', which says nothing about this component's namespace '"
                + (component.getNamespace() == null ? "" : component.getNamespace())
                + "' — and the legacy lookup never reads the namespace. The advisory store"
                + " carries no entry for this package under its purl."
                + suffix;
    }

    /** Why the legacy path never saw a vulnerability that purl matching found. */
    private static String missedByLegacyAssessment(
            ComponentEntity component, LegacyCpeProbe.Result legacy) {
        String candidates = String.join(", ", legacy.productCandidates());
        return "The legacy path generated the CPE product candidates ["
                + candidates
                + "] from the artifact name and none of them reaches this advisory, which was"
                + " published against the package's purl. The current firewall does not report"
                + " this vulnerability for "
                + component.getName()
                + " at all.";
    }

    /** Why the two methods disagree about the version rather than the package. */
    private static String versionDisagreementAssessment(
            ComponentEntity component, PackageURL purl, boolean legacyFlags) {
        String scheme = VersionSchemes.forPurlType(purl == null ? null : purl.getType()).id();
        return legacyFlags
                ? "Both methods identify the same package; they disagree about the version."
                        + " The generic VersionComparator sorts '"
                        + component.getVersion()
                        + "' inside the affected range, the "
                        + scheme
                        + " version scheme sorts it outside."
                : "Both methods identify the same package; they disagree about the version."
                        + " The "
                        + scheme
                        + " version scheme sorts '"
                        + component.getVersion()
                        + "' inside the affected range, the generic VersionComparator sorts it"
                        + " outside — the legacy path does not report this vulnerability.";
    }

    private AdvisoryStoreState readStoreState() {
        long affectedTotal = advisoryAffected.count();
        long cpeDerived = advisoryAffected.countByPurlType(CpePurlTranslator.PURL_TYPE);
        return new AdvisoryStoreState(
                cveEntries.count(),
                cveAffectedProducts.count(),
                advisories.count(),
                affectedTotal,
                affectedTotal - cpeDerived,
                cpeDerived);
    }

    /** Caveats derived from the run, so they cannot be stale boilerplate. */
    private static List<String> notes(
            ComparisonSummary summary, ComparisonReportRequest request, boolean truncated) {
        List<String> notes = new ArrayList<>();
        AdvisoryStoreState state = summary.storeState();

        if (!state.isComparable()) {
            notes.add("One side of the comparison has no data ("
                    + state.cveAffectedProducts()
                    + " CPE ranges, "
                    + state.advisoryAffectedTotal()
                    + " advisory ranges). The numbers below do not support any conclusion until"
                    + " both the NVD mirror and the advisory ingest have completed a sync.");
        }
        if (state.advisoryAffectedPurlNative() == 0) {
            notes.add("The advisory store holds no purl-native ranges at all — OSV and GHSA have"
                    + " not been ingested. Purl matching can only be compared fairly once they"
                    + " have.");
        }
        if (summary.componentsScanned() == 0) {
            notes.add("No components were scanned. Either the instance is empty or the"
                    + " repository filter matched nothing.");
        }
        if (truncated) {
            notes.add("The scan stopped at the configured limit of "
                    + request.maxComponents()
                    + " components. The counts describe that prefix, not the whole instance.");
        }
        if (summary.componentsUnidentified() > 0) {
            notes.add(count(summary.componentsUnidentified(), "component has", "components have")
                    + " no purl ("
                    + describeFormats(summary.unidentifiedByFormat())
                    + "). Purl matching reports nothing for them by construction, so every legacy"
                    + " finding on those components lands in the legacy-only bucket below. They are"
                    + " counted like every other component, but they mean 'not assessable', not"
                    + " 'false positive'.");
        }
        if (summary.findingsCpeOnlyStillReportedAsHeuristic() > 0) {
            notes.add(summary.findingsCpeOnlyStillReportedAsHeuristic()
                    + " of the legacy-only findings are still reported by the replacement through"
                    + " its CPE-derived pass, labelled HEURISTIC. They are downgraded for review,"
                    + " not removed, and must not be counted as eliminated false positives.");
        }
        if (summary.findingsVersionOnlyPurl() > 0) {
            notes.add(count(summary.findingsVersionOnlyPurl(), "finding is", "findings are")
                    + " reported only by purl matching because of the version ordering, i.e. the"
                    + " current firewall lets those downloads through today.");
        }
        notes.add("The purl side of the diff is the purl-native answer (MatchConfidence.EXACT)."
                + " Running the comparison against the CPE-derived pass as well would compare CPE"
                + " guessing with itself.");
        return notes;
    }

    /** "1 component has" / "4 components have" — the notes read as prose. */
    private static String count(long value, String singular, String plural) {
        return value + " " + (value == 1 ? singular : plural);
    }

    private static String describeFormats(Map<String, Long> byFormat) {
        StringBuilder out = new StringBuilder("formats: ");
        boolean first = true;
        for (Map.Entry<String, Long> entry : byFormat.entrySet()) {
            if (!first) {
                out.append(", ");
            }
            out.append(entry.getKey()).append(' ').append(entry.getValue());
            first = false;
        }
        return out.toString();
    }

    /** Collects counts over the whole run and keeps a bounded set of examples. */
    private static final class Accumulator {

        private final ComparisonReportRequest request;
        private final Map<DeltaKind, Integer> sampleCounts = new EnumMap<>(DeltaKind.class);
        private final List<ComponentComparison> samples = new ArrayList<>();
        private final TreeMap<String, Long> unidentifiedByFormat = new TreeMap<>();

        private long componentsScanned;
        private long componentsUnidentified;
        private long componentsBothClean;
        private long componentsInAgreement;
        private long componentsDivergent;
        private long componentsWithCpeOnly;
        private long componentsWithPurlOnly;
        private long componentsWithVersionDisagreement;
        private long findingsAgreed;
        private long findingsCpeOnly;
        private long findingsPurlOnly;
        private long findingsVersionOnlyCpe;
        private long findingsVersionOnlyPurl;
        private long findingsCpeOnlyStillHeuristic;

        Accumulator(ComparisonReportRequest request) {
            this.request = request;
        }

        void add(ComponentComparison comparison) {
            componentsScanned++;
            switch (comparison.verdict()) {
                case UNIDENTIFIED -> {
                    componentsUnidentified++;
                    String format = comparison.format() == null
                            ? "unknown"
                            : comparison.format().toLowerCase(Locale.ROOT);
                    unidentifiedByFormat.merge(format, 1L, Long::sum);
                }
                case BOTH_CLEAN -> componentsBothClean++;
                case AGREEMENT -> componentsInAgreement++;
                case DIVERGENT -> componentsDivergent++;
            }

            boolean hasCpeOnly = false;
            boolean hasPurlOnly = false;
            boolean hasVersionDisagreement = false;
            for (VulnerabilityDelta delta : comparison.deltas()) {
                switch (delta.kind()) {
                    case AGREED -> findingsAgreed++;
                    case CPE_ONLY -> {
                        findingsCpeOnly++;
                        hasCpeOnly = true;
                    }
                    case PURL_ONLY -> {
                        findingsPurlOnly++;
                        hasPurlOnly = true;
                    }
                    case VERSION_ONLY_CPE -> {
                        findingsVersionOnlyCpe++;
                        hasVersionDisagreement = true;
                    }
                    case VERSION_ONLY_PURL -> {
                        findingsVersionOnlyPurl++;
                        hasVersionDisagreement = true;
                    }
                }
                if (delta.alsoReportedAsHeuristic() && delta.kind() == DeltaKind.CPE_ONLY) {
                    findingsCpeOnlyStillHeuristic++;
                }
            }
            if (hasCpeOnly) {
                componentsWithCpeOnly++;
            }
            if (hasPurlOnly) {
                componentsWithPurlOnly++;
            }
            if (hasVersionDisagreement) {
                componentsWithVersionDisagreement++;
            }

            keepAsSample(comparison);
        }

        /**
         * Keeps the component as an example if any of its kinds still has room.
         * Quotas are per kind so a flood of one case cannot crowd the others out
         * of the report.
         */
        private void keepAsSample(ComponentComparison comparison) {
            boolean wanted = false;
            for (VulnerabilityDelta delta : comparison.deltas()) {
                if (delta.kind() == DeltaKind.AGREED && !request.includeAgreementSamples()) {
                    continue;
                }
                if (sampleCounts.getOrDefault(delta.kind(), 0) < request.maxSamplesPerKind()) {
                    wanted = true;
                }
            }
            // An unidentified component with no findings at all is still worth a
            // few examples: "we cannot say anything about these" is one of the
            // answers the report owes the reader.
            if (!wanted
                    && comparison.verdict() == ComparisonVerdict.UNIDENTIFIED
                    && comparison.deltas().isEmpty()
                    && countUnidentifiedSamples() < request.maxSamplesPerKind()) {
                wanted = true;
            }
            if (!wanted) {
                return;
            }
            samples.add(comparison);
            Set<DeltaKind> counted = new LinkedHashSet<>();
            for (VulnerabilityDelta delta : comparison.deltas()) {
                if (counted.add(delta.kind())) {
                    sampleCounts.merge(delta.kind(), 1, Integer::sum);
                }
            }
        }

        private long countUnidentifiedSamples() {
            return samples.stream()
                    .filter(sample -> sample.verdict() == ComparisonVerdict.UNIDENTIFIED)
                    .count();
        }

        List<ComponentComparison> samples() {
            return List.copyOf(samples);
        }

        ComparisonSummary summarise(AdvisoryStoreState storeState) {
            return new ComparisonSummary(
                    componentsScanned,
                    componentsUnidentified,
                    componentsBothClean,
                    componentsInAgreement,
                    componentsDivergent,
                    componentsWithCpeOnly,
                    componentsWithPurlOnly,
                    componentsWithVersionDisagreement,
                    findingsAgreed,
                    findingsCpeOnly,
                    findingsPurlOnly,
                    findingsVersionOnlyCpe,
                    findingsVersionOnlyPurl,
                    findingsCpeOnlyStillHeuristic,
                    unidentifiedByFormat,
                    storeState);
        }
    }
}
