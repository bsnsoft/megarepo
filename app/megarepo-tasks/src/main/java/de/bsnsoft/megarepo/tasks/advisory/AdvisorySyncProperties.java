package de.bsnsoft.megarepo.tasks.advisory;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration of the scheduled advisory sync.
 *
 * <p>Sits under {@code megarepo.firewall.advisory.sync}, next to the per-source
 * subtrees the sources already own ({@code …advisory.osv.*},
 * {@code …firewall.ghsa.*}). This one governs whether the sync <em>runs</em>,
 * not what any source does once it is running.
 *
 * <p>Two switches exist on purpose and mean different things: the
 * {@code scheduled_tasks} row is the operator's (visible in the UI, per
 * installation), this property is the deployment's (an environment variable, and
 * the one that works when the database is the thing you want to keep away from).
 *
 * @param enabled whether the registered task does anything when it fires
 */
@ConfigurationProperties(prefix = "megarepo.firewall.advisory.sync")
public record AdvisorySyncProperties(@DefaultValue("true") boolean enabled) {

    /** Defaults — the shape a deployment that never configured the sync gets. */
    public static AdvisorySyncProperties defaults() {
        return new AdvisorySyncProperties(true);
    }
}
