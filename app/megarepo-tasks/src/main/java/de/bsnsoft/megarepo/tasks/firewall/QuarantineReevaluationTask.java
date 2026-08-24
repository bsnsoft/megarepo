package de.bsnsoft.megarepo.tasks.firewall;

import de.bsnsoft.megarepo.repository.firewall.quarantine.QuarantineProperties;
import de.bsnsoft.megarepo.repository.firewall.quarantine.QuarantineService;
import de.bsnsoft.megarepo.tasks.TaskRunner;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * The scheduled half of automatic release.
 *
 * <p>Runs {@link QuarantineService#reevaluateDue} against the entries whose next
 * evaluation is due: the current policy against current data, releasing what has
 * become acceptable. The {@code scheduled_tasks} row is seeded by
 * {@code V19__firewall_phase2_tasks.sql} for every fifteen minutes with an
 * explicit {@code next_run}, so an operator can retime or disable it from the
 * Tasks page without a redeploy.
 *
 * <h2>One sweep, two triggers</h2>
 *
 * This task is not the only caller. {@code AdvisorySyncTask} runs the same sweep
 * immediately after a sync, because an advisory import is the single event most
 * likely to change an {@code UNKNOWN_COMPONENT} answer and waiting a quarter of
 * an hour after it is a quarter of an hour of a build failing for data that has
 * already arrived. Both go through {@link QuarantineService}: a release that only
 * happens on one of the two paths is a release nobody can predict.
 *
 * <h2>Why it cannot break a download</h2>
 *
 * A sweep only ever looks at components that are already being held, so the
 * worst it can do to a served download is nothing. The one state change that
 * denies anything — a re-evaluation finding a genuine policy violation and
 * moving the entry to {@code BLOCKED} — applies to a component that was not
 * being served in the first place.
 */
@Component
public class QuarantineReevaluationTask {

    /** Task type in {@code scheduled_tasks.type}; must match the V19 seed. */
    public static final String TASK_TYPE = "security.firewall.quarantine.reevaluate";

    private static final Logger log = LoggerFactory.getLogger(QuarantineReevaluationTask.class);

    private final QuarantineService quarantine;
    private final QuarantineProperties properties;
    private final TaskRunner taskRunner;

    public QuarantineReevaluationTask(
            QuarantineService quarantine, QuarantineProperties properties, TaskRunner taskRunner) {
        this.quarantine = quarantine;
        this.properties = properties;
        this.taskRunner = taskRunner;
    }

    @PostConstruct
    void register() {
        taskRunner.registerHandler(TASK_TYPE, this::execute);
    }

    /**
     * One sweep, bounded by {@code megarepo.firewall.quarantine.reevaluation-batch-size}.
     *
     * <p>Bounded rather than draining the queue: an installation that accumulated
     * five figures of held components during an advisory outage should not turn
     * one cron tick into an hour-long transaction. The remainder is picked up by
     * the next tick, oldest schedule first, which is the order
     * {@code findDueForReevaluation} already returns.
     */
    public void execute() {
        if (!properties.enabled()) {
            log.debug("Quarantine is disabled (megarepo.firewall.quarantine.enabled=false) — skipping");
            return;
        }
        int changed = quarantine.reevaluateDue(Instant.now(), properties.reevaluationBatchSize());
        log.info("Firewall quarantine re-evaluation finished: {} entries changed state", changed);
    }
}
