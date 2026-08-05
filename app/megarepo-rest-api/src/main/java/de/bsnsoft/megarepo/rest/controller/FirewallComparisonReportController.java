package de.bsnsoft.megarepo.rest.controller;

import de.bsnsoft.megarepo.repository.firewall.report.ComparisonReportMarkdown;
import de.bsnsoft.megarepo.repository.firewall.report.ComparisonReportRequest;
import de.bsnsoft.megarepo.repository.firewall.report.CpePurlComparisonReport;
import de.bsnsoft.megarepo.repository.firewall.report.CpePurlComparisonService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.format.DateTimeFormatter;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * The Phase 1 comparison report: current CPE matching against purl matching,
 * over this instance's own repository data.
 *
 * <h2>Why an endpoint and not a CLI or a Gradle task</h2>
 *
 * The customer has to be able to run this themselves against their real
 * components, and the report must not change anything. An HTTP endpoint on the
 * running instance is the only one of the three options that satisfies both
 * without side effects:
 *
 * <ul>
 *   <li>A <b>CLI mode</b> would boot a second application context against the
 *       same database. That context runs {@code FirstRunSetup} and the task
 *       scheduler, both of which write — a "read-only report" that seeds users
 *       and can fire a cleanup task is not read-only. Suppressing them would
 *       mean a second, differently-wired startup path maintained solely for a
 *       diagnostic.</li>
 *   <li>A <b>Gradle task</b> would need the customer to have the source tree,
 *       a JDK and the database credentials on the same machine. They run a
 *       container.</li>
 *   <li>An <b>endpoint</b> reuses the wiring that is already correct, reads
 *       through the same services the firewall will use, and needs nothing on
 *       the customer's side but {@code curl}.</li>
 * </ul>
 *
 * <p>Both representations come from the same computation:
 *
 * <pre>
 * # machine-readable
 * curl -u admin -H 'Accept: application/json' \
 *   'https://megarepo.example.com/api/v1/admin/firewall/cpe-purl-comparison' -o report.json
 *
 * # human-readable
 * curl -u admin \
 *   'https://megarepo.example.com/api/v1/admin/firewall/cpe-purl-comparison/markdown' -o report.md
 *
 * # scoped to two repositories, more worked examples
 * curl -u admin '…/cpe-purl-comparison/markdown?repositoryId=<uuid>&repositoryId=<uuid>&maxSamplesPerKind=100'
 * </pre>
 *
 * <h2>Access</h2>
 *
 * {@code /api/v1/admin/firewall/**} is restricted to the {@code nx-admin} role
 * in {@code SecurityConfig}, which is where this project expresses
 * authorization. It is deliberately not merely {@code authenticated()} like the
 * rest of {@code /api/v1/**}: the report enumerates every component in every
 * repository together with its known vulnerabilities, which is a map of what to
 * attack. The neighbouring {@code NvdFirewallController} has no authorization of
 * its own at all; that is a known gap, not a pattern to copy.
 *
 * <h2>Cost</h2>
 *
 * Read-only, paged, and bounded by {@code maxComponents}. It holds no
 * transaction across the run. It is still a full scan of {@code components} with
 * several indexed advisory queries each — run it deliberately, not on a
 * schedule.
 */
@RestController
@RequestMapping("/api/v1/admin/firewall/cpe-purl-comparison")
public class FirewallComparisonReportController {

    private static final DateTimeFormatter FILE_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final CpePurlComparisonService comparisonService;

    public FirewallComparisonReportController(CpePurlComparisonService comparisonService) {
        this.comparisonService = comparisonService;
    }

    /** The full report as JSON. */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CpePurlComparisonReport> json(
            @RequestParam(name = "repositoryId", required = false) List<UUID> repositoryIds,
            @RequestParam(defaultValue = "0") int pageSize,
            @RequestParam(defaultValue = "0") int maxComponents,
            @RequestParam(defaultValue = "0") int maxSamplesPerKind,
            @RequestParam(defaultValue = "false") boolean includeAgreementSamples) {

        CpePurlComparisonReport report = comparisonService.run(request(
                repositoryIds, pageSize, maxComponents, maxSamplesPerKind, includeAgreementSamples));
        return ResponseEntity.ok(report);
    }

    /** The same report rendered for a human reader. */
    @GetMapping(value = "/markdown", produces = "text/markdown;charset=UTF-8")
    public ResponseEntity<String> markdown(
            @RequestParam(name = "repositoryId", required = false) List<UUID> repositoryIds,
            @RequestParam(defaultValue = "0") int pageSize,
            @RequestParam(defaultValue = "0") int maxComponents,
            @RequestParam(defaultValue = "0") int maxSamplesPerKind,
            @RequestParam(defaultValue = "false") boolean includeAgreementSamples) {

        CpePurlComparisonReport report = comparisonService.run(request(
                repositoryIds, pageSize, maxComponents, maxSamplesPerKind, includeAgreementSamples));
        String filename =
                "megarepo-cpe-purl-comparison-" + FILE_TIMESTAMP.format(report.generatedAt()) + ".md";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(ComparisonReportMarkdown.render(report));
    }

    /**
     * Zero means "use the default" for every numeric parameter; the request
     * record clamps the rest. A diagnostic that answers a typo with a 400 sends
     * the operator to the source instead of to the report.
     */
    private static ComparisonReportRequest request(
            List<UUID> repositoryIds,
            int pageSize,
            int maxComponents,
            int maxSamplesPerKind,
            boolean includeAgreementSamples) {
        return new ComparisonReportRequest(
                repositoryIds == null ? List.of() : repositoryIds,
                pageSize,
                maxComponents,
                maxSamplesPerKind,
                includeAgreementSamples,
                null);
    }
}
