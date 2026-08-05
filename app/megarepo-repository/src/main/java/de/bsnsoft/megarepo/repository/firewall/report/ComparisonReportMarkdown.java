package de.bsnsoft.megarepo.repository.firewall.report;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Renders a {@link CpePurlComparisonReport} as Markdown.
 *
 * <p>The JSON form of the report is what we evaluate; this is what the customer
 * reads, and the two come from the same value so they cannot disagree. Pure and
 * stateless — no I/O, no formatting that depends on the default locale.
 *
 * <p>Ordering of the sections is an argument, not a layout: how much data each
 * side had, then the aggregate, then the cases where the legacy path
 * over-reports, then the cases where it under-reports, then the version
 * disagreements, then what neither side can assess, and finally the method. A
 * reader who stops after the summary has still seen the caveats.
 */
public final class ComparisonReportMarkdown {

    private ComparisonReportMarkdown() {}

    public static String render(CpePurlComparisonReport report) {
        StringBuilder out = new StringBuilder(8_192);

        out.append("# Repository Firewall — CPE matching vs. purl matching\n\n");
        out.append("Phase 1 measurement, osTicket #155155. Both methods were run over the same\n")
                .append("components from the same database; neither wrote anything.\n\n");

        if (report.synthetic()) {
            out.append("> **These numbers are from a synthetic fixture, not from real repository ")
                    .append("data.**\n> ")
                    .append(report.request().datasetLabel())
                    .append("\n> The fixture exists to show that the comparison classifies the known ")
                    .append("cases correctly\n> and to show the shape of the output. It says nothing ")
                    .append("about how often each case\n> occurs in a real repository.\n\n");
        }

        out.append("- Dataset: ").append(report.request().datasetLabel()).append('\n');
        out.append("- Generated: ").append(report.generatedAt()).append('\n');
        out.append("- Scan duration: ").append(humanDuration(report.duration())).append('\n');
        out.append("- Components scanned: ")
                .append(report.summary().componentsScanned())
                .append(report.truncated() ? " (stopped at the configured limit)" : "")
                .append("\n\n");

        appendStoreState(out, report.summary().storeState());
        appendSummary(out, report.summary());
        appendNotes(out, report.notes());

        appendCaseSection(
                out,
                report,
                DeltaKind.CPE_ONLY,
                "1. Only the current CPE matching flags it — suspected false positives",
                "The legacy lookup reports these; purl matching does not find the package under "
                        + "this advisory at all. Each row names the CPE product that matched and why "
                        + "that match does not hold.");

        appendCaseSection(
                out,
                report,
                DeltaKind.PURL_ONLY,
                "2. Only purl matching flags it — vulnerabilities the current firewall misses",
                "The advisory names the package by purl. The legacy candidate generation never "
                        + "produces a CPE product that reaches it, so the running firewall does not "
                        + "report these today.");

        appendVersionSection(out, report);

        appendUnidentifiedSection(out, report);

        appendAgreementSection(out, report);

        appendMethod(out);
        return out.toString();
    }

    private static void appendStoreState(StringBuilder out, AdvisoryStoreState state) {
        out.append("## Data both sides had\n\n");
        out.append("| Table | Rows |\n|---|---:|\n");
        out.append("| `cve_entries` (legacy NVD mirror) | ").append(state.cveEntries()).append(" |\n");
        out.append("| `cve_affected_products` (CPE ranges) | ")
                .append(state.cveAffectedProducts())
                .append(" |\n");
        out.append("| `advisory` | ").append(state.advisories()).append(" |\n");
        out.append("| `advisory_affected`, purl-native (OSV, GHSA) | ")
                .append(state.advisoryAffectedPurlNative())
                .append(" |\n");
        out.append("| `advisory_affected`, CPE-derived (NVD) | ")
                .append(state.advisoryAffectedCpeDerived())
                .append(" |\n\n");
        if (!state.isComparable()) {
            out.append("**One side has no data. Nothing below is a valid comparison.**\n\n");
        }
    }

    private static void appendSummary(StringBuilder out, ComparisonSummary summary) {
        out.append("## Summary\n\n");

        out.append("### Vulnerabilities reported\n\n");
        out.append("One row per vulnerability per component; the five kinds are a partition.\n\n");
        out.append("| Outcome | Count |\n|---|---:|\n");
        out.append("| Both methods report it | ").append(summary.findingsAgreed()).append(" |\n");
        out.append("| Only CPE matching reports it (suspected false positive) | ")
                .append(summary.findingsCpeOnly())
                .append(" |\n");
        out.append("| Only purl matching reports it (missed today) | ")
                .append(summary.findingsPurlOnly())
                .append(" |\n");
        out.append("| Same package, only CPE matching calls the version affected | ")
                .append(summary.findingsVersionOnlyCpe())
                .append(" |\n");
        out.append("| Same package, only purl matching calls the version affected | ")
                .append(summary.findingsVersionOnlyPurl())
                .append(" |\n");
        out.append("| **Legacy path total** | **")
                .append(summary.legacyFindingsTotal())
                .append("** |\n");
        out.append("| **purl path total** | **")
                .append(summary.purlFindingsTotal())
                .append("** |\n\n");

        out.append("Of the ")
                .append(summary.legacyFindingsTotal())
                .append(" reports the current firewall produces, ")
                .append(summary.findingsCpeOnly() + summary.findingsVersionOnlyCpe())
                .append(" are not reproduced by purl matching (")
                .append(percent(summary.legacyReportsNotReproduced()))
                .append("). ")
                .append(summary.findingsCpeOnlyStillReportedAsHeuristic())
                .append(" of those are still surfaced by the replacement through its CPE-derived ")
                .append("pass, labelled `HEURISTIC` — downgraded, not removed.\n\n");

        out.append("### Components\n\n");
        out.append("The four verdicts partition the scan; the three `with …` rows below them ")
                .append("overlap.\n\n");
        out.append("| Verdict | Components |\n|---|---:|\n");
        out.append("| Both methods clean | ").append(summary.componentsBothClean()).append(" |\n");
        out.append("| Both methods agree on every finding | ")
                .append(summary.componentsInAgreement())
                .append(" |\n");
        out.append("| Methods disagree | ").append(summary.componentsDivergent()).append(" |\n");
        out.append("| Not identifiable (no purl) | ")
                .append(summary.componentsUnidentified())
                .append(" |\n");
        out.append("| **Scanned** | **").append(summary.componentsScanned()).append("** |\n\n");
        out.append("| Overlapping | Components |\n|---|---:|\n");
        out.append("| with a suspected false positive | ")
                .append(summary.componentsWithCpeOnly())
                .append(" |\n");
        out.append("| with a missed vulnerability | ")
                .append(summary.componentsWithPurlOnly())
                .append(" |\n");
        out.append("| with a version disagreement | ")
                .append(summary.componentsWithVersionDisagreement())
                .append(" |\n\n");

        if (!summary.unidentifiedByFormat().isEmpty()) {
            out.append("Not identifiable, by format: ");
            boolean first = true;
            for (Map.Entry<String, Long> entry : summary.unidentifiedByFormat().entrySet()) {
                if (!first) {
                    out.append(", ");
                }
                out.append('`').append(entry.getKey()).append("` ").append(entry.getValue());
                first = false;
            }
            out.append(".\n\n");
        }
    }

    private static void appendNotes(StringBuilder out, List<String> notes) {
        if (notes.isEmpty()) {
            return;
        }
        out.append("## Read this before the numbers\n\n");
        for (String note : notes) {
            out.append("- ").append(note).append('\n');
        }
        out.append('\n');
    }

    private static void appendCaseSection(
            StringBuilder out,
            CpePurlComparisonReport report,
            DeltaKind kind,
            String heading,
            String lede) {

        long total = countOf(report.summary(), kind);
        out.append("## ").append(heading).append("\n\n");
        out.append(lede).append("\n\n");

        List<ComponentComparison> samples = report.samplesOf(kind);
        if (total == 0) {
            out.append("None.\n\n");
            return;
        }
        out.append("Showing ")
                .append(countDeltas(samples, kind))
                .append(" of ")
                .append(total)
                .append(".\n\n");

        for (ComponentComparison sample : samples) {
            for (VulnerabilityDelta delta : sample.deltas()) {
                if (delta.kind() != kind) {
                    continue;
                }
                appendCase(out, sample, delta);
            }
        }
    }

    private static void appendVersionSection(StringBuilder out, CpePurlComparisonReport report) {
        long total = report.summary().findingsVersionOnlyCpe()
                + report.summary().findingsVersionOnlyPurl();
        out.append("## 3. Same package, different version verdict\n\n");
        out.append("Both methods identify the component as the same package and disagree only ")
                .append("about whether its version falls inside the published range. This is the ")
                .append("generic `VersionComparator` against the per-ecosystem `VersionScheme`, ")
                .append("with the package identity held constant.\n\n");

        if (total == 0) {
            out.append("None.\n\n");
            return;
        }

        List<ComponentComparison> cpeSide = report.samplesOf(DeltaKind.VERSION_ONLY_CPE);
        List<ComponentComparison> purlSide = report.samplesOf(DeltaKind.VERSION_ONLY_PURL);
        out.append("Showing ")
                .append(countDeltas(cpeSide, DeltaKind.VERSION_ONLY_CPE)
                        + countDeltas(purlSide, DeltaKind.VERSION_ONLY_PURL))
                .append(" of ")
                .append(total)
                .append(" — ")
                .append(report.summary().findingsVersionOnlyCpe())
                .append(" where only the legacy comparator flags the version, ")
                .append(report.summary().findingsVersionOnlyPurl())
                .append(" where only the version scheme does.\n\n");

        for (ComponentComparison sample : cpeSide) {
            for (VulnerabilityDelta delta : sample.deltas()) {
                if (delta.kind() == DeltaKind.VERSION_ONLY_CPE) {
                    appendCase(out, sample, delta);
                }
            }
        }
        for (ComponentComparison sample : purlSide) {
            for (VulnerabilityDelta delta : sample.deltas()) {
                if (delta.kind() == DeltaKind.VERSION_ONLY_PURL) {
                    appendCase(out, sample, delta);
                }
            }
        }
    }

    private static void appendUnidentifiedSection(
            StringBuilder out, CpePurlComparisonReport report) {
        out.append("## 4. Not identifiable — no purl\n\n");
        out.append("Raw files and Docker tags carry no package coordinates. No advisory feed ")
                .append("indexes them, so purl matching reports nothing for these by construction ")
                .append("and the firewall falls back to content-hash identity. They are listed ")
                .append("because the legacy path does still match them on the file name, and ")
                .append("because their number bounds what this report can claim.\n\n");

        long unidentified = report.summary().componentsUnidentified();
        if (unidentified == 0) {
            out.append("None.\n\n");
            return;
        }

        List<ComponentComparison> samples = report.samples().stream()
                .filter(sample -> sample.verdict() == ComparisonVerdict.UNIDENTIFIED)
                .toList();
        out.append("Showing ").append(samples.size()).append(" of ").append(unidentified).append(".\n\n");
        out.append("| Component | Format | Identity | Legacy findings |\n|---|---|---|---:|\n");
        for (ComponentComparison sample : samples) {
            out.append("| `")
                    .append(sample.coordinates())
                    .append("` | `")
                    .append(nullSafe(sample.format()))
                    .append("` | `")
                    .append(nullSafe(sample.identityKey()))
                    .append("` | ")
                    .append(sample.deltas().size())
                    .append(" |\n");
        }
        out.append('\n');
    }

    private static void appendAgreementSection(StringBuilder out, CpePurlComparisonReport report) {
        out.append("## 5. Agreement\n\n");
        out.append(report.summary().findingsAgreed())
                .append(" vulnerabilities are reported identically by both methods, over ")
                .append(report.summary().componentsInAgreement())
                .append(" components where every finding agreed. Purl identity changes nothing ")
                .append("for these — which is the point: the replacement is not a different ")
                .append("opinion about everything, it is a narrower one about the cases above.\n\n");

        List<ComponentComparison> samples = report.samplesOf(DeltaKind.AGREED);
        if (samples.isEmpty()) {
            return;
        }
        for (ComponentComparison sample : samples) {
            for (VulnerabilityDelta delta : sample.deltas()) {
                if (delta.kind() == DeltaKind.AGREED) {
                    appendCase(out, sample, delta);
                }
            }
        }
    }

    private static void appendCase(
            StringBuilder out, ComponentComparison sample, VulnerabilityDelta delta) {
        out.append("### ")
                .append(delta.vulnerabilityId())
                .append(" — `")
                .append(sample.coordinates())
                .append("`\n\n");
        out.append("| | |\n|---|---|\n");
        out.append("| Component | `")
                .append(nullSafe(sample.format()))
                .append("` `")
                .append(sample.coordinates())
                .append("` |\n");
        out.append("| Firewall identity | `").append(nullSafe(sample.identityKey())).append("` |\n");
        if (delta.severity() != null || delta.cvssScore() != null) {
            out.append("| Severity | ")
                    .append(nullSafe(delta.severity()))
                    .append(delta.cvssScore() == null
                            ? ""
                            : " (CVSS " + format(delta.cvssScore()) + ")")
                    .append(" |\n");
        }
        out.append("| CPE match | ")
                .append(delta.cpeEvidence() == null ? "— no match" : "`" + delta.cpeEvidence() + "`")
                .append(" |\n");
        out.append("| purl match | ")
                .append(delta.purlEvidence() == null ? "— no match" : "`" + delta.purlEvidence() + "`")
                .append(" |\n");
        if (delta.alsoReportedAsHeuristic()) {
            out.append("| Still reported? | yes, by the CPE-derived pass, labelled `HEURISTIC` |\n");
        }
        out.append('\n').append(delta.assessment()).append("\n\n");
    }

    private static void appendMethod(StringBuilder out) {
        out.append("## Method\n\n");
        out.append("- Both sides read the same local database. Neither makes a network call, ")
                .append("and the report writes nothing.\n");
        out.append("- The legacy side calls `NvdCveLookupService`'s own candidate generation and ")
                .append("version predicate, not a copy, so it measures the code that ships today.\n");
        out.append("- The purl side is `AdvisoryLookupService` with the CPE-derived pass off — the ")
                .append("purl-native answer at `EXACT` confidence. Where the CPE-derived pass would ")
                .append("still surface a legacy-only finding, that is stated per case.\n");
        out.append("- A vulnerability is counted once. Purl findings carry every alias the merge ")
                .append("collapsed, so a CVE reported by NVD and the GHSA that aliases it are one ")
                .append("agreement, not one agreement plus one false positive.\n");
        out.append("- \"Suspected false positive\" means purl matching does not reproduce the ")
                .append("report. Proving the component is genuinely unaffected needs the advisory ")
                .append("to name the package by purl; each case states which situation applies.\n");
    }

    private static long countOf(ComparisonSummary summary, DeltaKind kind) {
        return switch (kind) {
            case AGREED -> summary.findingsAgreed();
            case CPE_ONLY -> summary.findingsCpeOnly();
            case PURL_ONLY -> summary.findingsPurlOnly();
            case VERSION_ONLY_CPE -> summary.findingsVersionOnlyCpe();
            case VERSION_ONLY_PURL -> summary.findingsVersionOnlyPurl();
        };
    }

    private static long countDeltas(List<ComponentComparison> samples, DeltaKind kind) {
        return samples.stream()
                .flatMap(sample -> sample.deltas().stream())
                .filter(delta -> delta.kind() == kind)
                .count();
    }

    private static String percent(double fraction) {
        return String.format(Locale.ROOT, "%.1f %%", fraction * 100.0);
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static String humanDuration(Duration duration) {
        if (duration == null) {
            return "unknown";
        }
        long millis = duration.toMillis();
        return millis < 1_000
                ? millis + " ms"
                : String.format(Locale.ROOT, "%.1f s", millis / 1_000.0);
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
