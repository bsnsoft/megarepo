package de.bsnsoft.megarepo.tasks.firewall;

import de.bsnsoft.megarepo.repository.firewall.exemption.ExemptionProperties;
import de.bsnsoft.megarepo.repository.firewall.exemption.ExemptionService;
import de.bsnsoft.megarepo.repository.firewall.exemption.FirewallExemption;
import de.bsnsoft.megarepo.tasks.TaskRunner;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Handler for the {@code security.firewall.exemption.expiry} row V19 seeds:
 * lapses what has run out, and warns about what is about to.
 *
 * <h2>Why the flip is stored rather than derived</h2>
 *
 * {@code findApplicable} already filters on {@code expires_at}, so an exemption
 * stops letting downloads through the moment it lapses whether or not this task
 * has run — the sweep is not what makes expiry take effect. What it makes is
 * <em>agreement</em>: without it the exemption list would keep showing
 * {@code APPROVED} for a row that stopped applying at noon, and the first person
 * to discover the difference is whoever's build broke. A stored transition means
 * the list, the violation log and the operator all say the same thing.
 *
 * <h2>This task can only ever loosen or tighten in the announced direction</h2>
 *
 * Expiring an exemption does make the firewall refuse something it served
 * yesterday — that is the entire point of an expiry, and it is the one place in
 * Phase 2 where a background job can change a download's outcome for the worse.
 * Hence the notice: {@link ExemptionService#notifyUpcomingExpiry} runs first, so
 * an exemption's last week is spent warning rather than surprising, and the
 * warning goes out once per exemption.
 *
 * <p>Nothing runs at startup. {@link #register()} registers the handler and
 * returns; the seeded row's {@code next_run} is two hours out.
 */
@Component
public class ExemptionExpiryTask {

    /** Task type in {@code scheduled_tasks.type}, matching V19. */
    public static final String TASK_TYPE = "security.firewall.exemption.expiry";

    private static final Logger log = LoggerFactory.getLogger(ExemptionExpiryTask.class);

    private final ExemptionService exemptions;
    private final ExemptionProperties properties;
    private final TaskRunner taskRunner;

    public ExemptionExpiryTask(
            ExemptionService exemptions, ExemptionProperties properties, TaskRunner taskRunner) {
        this.exemptions = exemptions;
        this.properties = properties;
        this.taskRunner = taskRunner;
    }

    @PostConstruct
    void register() {
        taskRunner.registerHandler(TASK_TYPE, this::execute);
    }

    /**
     * Notices first, then expiries.
     *
     * <p>Order matters at the boundary: an exemption that lapses inside this very
     * run would otherwise be announced as "expires soon" in the same breath as
     * being expired, which is a notification nobody can act on. Announcing first
     * and expiring second means the notice window is genuinely a window.
     *
     * <p>Both halves are counted into one log line rather than left to the caller
     * to reconstruct from the task's duration: "0 expired, 3 announced" is what
     * an operator wants from the Tasks page, and an exception is what they want
     * when the sweep could not run at all.
     */
    public void execute() {
        Instant now = Instant.now();

        List<FirewallExemption> announced =
                exemptions.notifyUpcomingExpiry(now, properties.expiryNoticeLead());
        int expired = exemptions.expireLapsed(now);

        if (expired == 0 && announced.isEmpty()) {
            log.debug("Firewall exemption sweep: nothing lapsed and nothing lapses within {}",
                    properties.expiryNoticeLead());
            return;
        }
        log.info(
                "Firewall exemption sweep: {} expired, {} announced as lapsing within {}",
                expired, announced.size(), properties.expiryNoticeLead());
    }
}
