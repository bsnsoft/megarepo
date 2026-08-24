package de.bsnsoft.megarepo.tasks.advisory;

import de.bsnsoft.megarepo.repository.advisory.AdvisoryIngestService;
import de.bsnsoft.megarepo.repository.advisory.AdvisorySource;
import de.bsnsoft.megarepo.repository.advisory.AdvisorySyncSummary;
import de.bsnsoft.megarepo.repository.firewall.quarantine.QuarantineProperties;
import de.bsnsoft.megarepo.repository.firewall.quarantine.QuarantineService;
import de.bsnsoft.megarepo.tasks.TaskRunner;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Keeps the local advisory mirror current, on a schedule.
 *
 * <p>Modelled on {@code NvdSyncTask}: a handler registered under a task type,
 * driven by a {@code scheduled_tasks} row that carries the cron expression. The
 * row is seeded by {@code V15__advisory_sync_task.sql} for 02:30 daily, half an
 * hour before the existing NVD mirror sync at 03:00 — the NVD advisory
 * <em>source</em> reads that mirror, so running after it would mean acting on
 * data a day older than necessary, and running at the same time would have both
 * touching the same tables.
 *
 * <h2>Nothing happens at startup</h2>
 *
 * {@link #register()} registers a handler and returns. The first import is a
 * large one — OSV ships its ecosystems as zip archives — and starting it while
 * the application is still coming up would turn every restart into an outage
 * risk for no benefit. The seeded row's {@code next_run} is set an hour into the
 * future for the same reason.
 *
 * <h2>Why this task exists at all</h2>
 *
 * The advisory lookup on the download path reads nothing but the local tables,
 * which is what keeps a request thread off the network. That trade only works if
 * something else fills those tables — this is that something.
 */
@Component
@EnableConfigurationProperties(AdvisorySyncProperties.class)
public class AdvisorySyncTask {

    /** Task type in {@code scheduled_tasks.type}. */
    public static final String TASK_TYPE = "security.advisory.sync";

    private static final Logger log = LoggerFactory.getLogger(AdvisorySyncTask.class);

    private final AdvisoryIngestService ingestService;
    private final ObjectProvider<AdvisorySource> sources;
    private final TaskRunner taskRunner;
    private final AdvisorySyncProperties properties;
    private final ObjectProvider<QuarantineService> quarantine;
    private final QuarantineProperties quarantineProperties;

    public AdvisorySyncTask(
            AdvisoryIngestService ingestService,
            ObjectProvider<AdvisorySource> sources,
            TaskRunner taskRunner,
            AdvisorySyncProperties properties,
            ObjectProvider<QuarantineService> quarantine,
            QuarantineProperties quarantineProperties) {
        this.ingestService = ingestService;
        this.sources = sources;
        this.taskRunner = taskRunner;
        this.properties = properties;
        this.quarantine = quarantine;
        this.quarantineProperties = quarantineProperties;
    }

    @PostConstruct
    void register() {
        taskRunner.registerHandler(TASK_TYPE, this::execute);
    }

    /**
     * Syncs every configured source.
     *
     * <p>{@link AdvisoryIngestService#syncAll} never throws and isolates the
     * sources from each other, so a rate-limited GitHub cannot stop OSV. This
     * method therefore has to decide for itself what counts as a failed
     * <em>task</em>, and it draws the line at "every source failed": a partial
     * result still moved the mirror forward and the per-source detail is in
     * {@code advisory_sync_state}, whereas a total failure is something the task
     * list should show in red.
     */
    public void execute() {
        if (!properties.enabled()) {
            log.info("Advisory sync is disabled (megarepo.firewall.advisory.sync.enabled=false) — skipping");
            return;
        }
        List<AdvisorySource> configured = sources.orderedStream().toList();
        if (configured.isEmpty()) {
            log.warn("Advisory sync has no sources configured — nothing to do");
            return;
        }

        List<AdvisorySyncSummary> summaries = ingestService.syncAll(configured);
        long failed = summaries.stream().filter(summary -> !summary.succeeded()).count();
        for (AdvisorySyncSummary summary : summaries) {
            if (summary.succeeded()) {
                log.info("Advisory sync {}: {} ingested, {} skipped, {} batch(es)",
                        summary.source(), summary.ingested(), summary.skipped(), summary.batches());
            } else {
                log.warn("Advisory sync {} failed: {}", summary.source(), summary.errorMessage());
            }
        }

        if (failed == summaries.size()) {
            throw new IllegalStateException(
                    "Every advisory source failed (%d of %d); see advisory_sync_state for details"
                            .formatted(failed, summaries.size()));
        }
        if (failed > 0) {
            log.warn("Advisory sync finished with {} of {} sources failing", failed, summaries.size());
        }

        reevaluateQuarantine();
    }

    /**
     * Re-runs the quarantine sweep now that new advisory data is in.
     *
     * <p>A sync is the single event most likely to change an
     * {@code UNKNOWN_COMPONENT} answer — the data the firewall was waiting for
     * has just arrived — and the scheduled sweep runs every fifteen minutes.
     * Waiting for it would be up to a quarter of an hour of a build failing for
     * information the instance already has. The same
     * {@link QuarantineService#reevaluateDue} the scheduled task calls: one code
     * path, two triggers.
     *
     * <p>Never fails the sync. The import succeeded; a sweep that did not is a
     * separate problem, and reporting the task in red for it would send an
     * operator looking at the advisory sources for a fault that is not there. The
     * next scheduled sweep picks the entries up regardless.
     *
     * <p>Switchable off with
     * {@code megarepo.firewall.quarantine.reevaluate-after-advisory-sync=false},
     * and skipped entirely when quarantine is disabled. The service is resolved
     * through an {@link ObjectProvider} so a deployment assembled without the
     * quarantine bean still syncs advisories.
     */
    private void reevaluateQuarantine() {
        if (!quarantineProperties.enabled() || !quarantineProperties.reevaluateAfterAdvisorySync()) {
            return;
        }
        QuarantineService service = quarantine.getIfAvailable();
        if (service == null) {
            return;
        }
        try {
            int changed = service.reevaluateDue(
                    Instant.now(), quarantineProperties.reevaluationBatchSize());
            if (changed > 0) {
                log.info("Advisory sync released or blocked {} quarantined component(s)", changed);
            }
        } catch (RuntimeException e) {
            log.warn("Post-sync quarantine re-evaluation failed; the scheduled sweep will retry", e);
        }
    }
}
