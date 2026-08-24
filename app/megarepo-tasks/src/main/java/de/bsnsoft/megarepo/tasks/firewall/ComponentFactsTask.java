package de.bsnsoft.megarepo.tasks.firewall;

import de.bsnsoft.megarepo.repository.firewall.facts.ComponentFactsResolver;
import de.bsnsoft.megarepo.tasks.TaskRunner;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The scheduled half of component-facts resolution, seeded by
 * {@code V19__firewall_phase2_tasks.sql} as
 * {@code security.firewall.facts.resolve} every 10 minutes.
 *
 * <p>Same pattern as {@code AdvisorySyncTask}: a handler registered against a
 * task type, with the cron expression living in a {@code scheduled_tasks} row so
 * an operator can retime or disable it from the Tasks page without a redeploy.
 *
 * <h2>The sweep is not the only path, and that is the point</h2>
 *
 * The resolver drains its own queue continuously — a download that was held
 * because nobody knows how old a package is must not wait a quarter of an hour
 * for a cron tick. What this task adds is the rows an in-process queue cannot
 * survive a restart with, and the settled rows old enough to be worth asking
 * about again.
 *
 * <p>Nothing here can turn a served download into a refused one: it only moves
 * components from "unknown" towards a settled answer, and the rules that read
 * those answers treat a settled "cannot know" as grounds to serve.
 */
@Component
public class ComponentFactsTask {

    /** Task type in {@code scheduled_tasks.type}; must match V19. */
    public static final String TASK_TYPE = "security.firewall.facts.resolve";

    private static final Logger log = LoggerFactory.getLogger(ComponentFactsTask.class);

    private final ComponentFactsResolver resolver;
    private final TaskRunner taskRunner;

    public ComponentFactsTask(ComponentFactsResolver resolver, TaskRunner taskRunner) {
        this.resolver = resolver;
        this.taskRunner = taskRunner;
    }

    @PostConstruct
    void register() {
        taskRunner.registerHandler(TASK_TYPE, this::execute);
    }

    /**
     * Queues everything due.
     *
     * <p>Queues rather than resolves: the resolver's pool is where the outbound
     * requests belong, and a task that did the fetching itself would either
     * duplicate that pool's work or hold the scheduler thread for as long as the
     * slowest registry takes.
     */
    public void execute() {
        int queued = resolver.sweep();
        log.debug("Component facts task queued {} row(s)", queued);
    }
}
