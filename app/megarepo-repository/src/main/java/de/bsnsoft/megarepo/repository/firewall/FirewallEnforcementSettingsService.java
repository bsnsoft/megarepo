package de.bsnsoft.megarepo.repository.firewall;

import de.bsnsoft.megarepo.database.entity.FirewallEnforcementSettingsEntity;
import de.bsnsoft.megarepo.database.repository.FirewallEnforcementSettingsJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The global firewall enforcement switch, and the only thing that may turn a
 * recorded violation into a denied download.
 *
 * <h2>Two switches, both of which have to say yes</h2>
 *
 * <ol>
 *   <li>This one — global, off by default.</li>
 *   <li>The repository's {@code firewall_repository_config.mode}, which has to
 *       be {@code QUARANTINE}.</li>
 * </ol>
 *
 * <p>The global switch exists because the per-repository one is not safe on its
 * own: {@code QUARANTINE} was already an accepted value in Phase 1, where it
 * behaved exactly like {@code AUDIT}, so an installation upgrading into this
 * build can perfectly well have repositories already set to it. If enforcement
 * followed the repository mode alone, the upgrade itself would start denying
 * downloads — which is precisely what the customer ruled out ("no existing setup
 * may break on upgrade"). It is also the one-step undo when a policy turns out
 * to be too strict.
 *
 * <h2>Where the value comes from</h2>
 *
 * The same layering as {@code megarepo.outbound-proxy.*} (see
 * {@link de.bsnsoft.megarepo.repository.proxy.OutboundProxySettingsService}):
 * the singleton {@code firewall_enforcement_settings} row wins once it has been
 * written ({@code configured = true}), otherwise the deployment-side
 * {@link FirewallEnforcementProperties#enabled()} applies. That keeps env-only
 * installs working and still lets the switch be flipped at runtime.
 *
 * <h2>Why it is not a {@code @Value} field</h2>
 *
 * A bound property is read once at startup, and this switch has to be usable
 * without a restart — the operator turning enforcement off is usually doing it
 * because builds are failing right now. The value is therefore resolved from the
 * database and held in an {@link AtomicReference} that
 * {@link #save(boolean, String)} refreshes immediately and that otherwise
 * expires after
 * {@link FirewallEnforcementProperties#settingsRefreshInterval()}. The cache is
 * not an optimisation detail: the switch is consulted on <em>every</em>
 * download, and a database round trip per artifact is not acceptable, while a
 * one-interval delay for a change made behind this service's back is.
 *
 * <h2>The grandfathering watermark</h2>
 *
 * {@link #enforcingSince()} is stamped the first time this service observes
 * enforcement to be on, and never moved backwards afterwards. Components whose
 * stored asset predates it were already in the repository when the operator
 * flipped the switch and are audited but never blocked. Until it is stamped —
 * including before the first successful database read — it is null, and a null
 * watermark grandfathers everything, so the failure direction is "serve".
 *
 * <p>Nothing here throws at a caller. A database problem leaves the last known
 * snapshot in place; if there never was one, enforcement is off.
 */
@Service
public class FirewallEnforcementSettingsService {

    private static final Logger log = LoggerFactory.getLogger(FirewallEnforcementSettingsService.class);

    static final Integer SETTINGS_ID = 1;

    private final FirewallEnforcementSettingsJpaRepository repository;
    private final FirewallEnforcementProperties properties;
    private final AtomicReference<Snapshot> snapshot;

    public FirewallEnforcementSettingsService(
            FirewallEnforcementSettingsJpaRepository repository,
            FirewallEnforcementProperties properties) {
        this.repository = repository;
        this.properties = properties;
        // Before the first database read the deployment-side value is all there
        // is. The watermark stays null, which grandfathers every component, so
        // an install configured for enforcement does not deny anything in the
        // window between context start and the first successful read.
        this.snapshot = new AtomicReference<>(
                new Snapshot(properties.enabled(), null, Long.MIN_VALUE));
    }

    /**
     * Whether the firewall may deny downloads at all right now.
     *
     * <p>Called on every download. Reads memory, not the database, except once
     * per {@link FirewallEnforcementProperties#settingsRefreshInterval()}.
     */
    public boolean enforcementEnabled() {
        return current().enabled();
    }

    /**
     * When enforcement was first switched on, or null if it never was.
     *
     * <p>Null means "grandfather everything" — see the class comment.
     */
    public Instant enforcingSince() {
        return current().enforcingSince();
    }

    /** Reads the persisted row, creating it if the migration's seed is gone. */
    @Transactional
    public FirewallEnforcementSettingsEntity load() {
        return repository.findById(SETTINGS_ID).orElseGet(() -> {
            FirewallEnforcementSettingsEntity fresh = new FirewallEnforcementSettingsEntity();
            fresh.setId(SETTINGS_ID);
            return repository.save(fresh);
        });
    }

    /**
     * Flips the master switch and makes the change effective immediately, on
     * this node without a restart and on every other node within one refresh
     * interval.
     *
     * <p>Enabling stamps {@link #enforcingSince()} if it has never been stamped.
     * Disabling deliberately leaves it alone: if turning enforcement off and on
     * again reset the watermark, everything pulled in while it was off would be
     * grandfathered, and the switch would quietly weaken the firewall every time
     * it was used.
     *
     * @param enabled the new state of the switch
     * @param updatedBy who asked for it, for the audit trail
     * @return the persisted row
     */
    @Transactional
    public FirewallEnforcementSettingsEntity save(boolean enabled, String updatedBy) {
        FirewallEnforcementSettingsEntity entity = load();
        boolean was = entity.isEnabled() && entity.isConfigured();
        entity.setConfigured(true);
        entity.setEnabled(enabled);
        if (enabled && entity.getEnforcingSince() == null) {
            entity.setEnforcingSince(Instant.now());
        }
        entity.setUpdatedAt(Instant.now());
        entity.setUpdatedBy(updatedBy);
        FirewallEnforcementSettingsEntity saved = repository.save(entity);
        publish(saved);
        log.warn("Repository firewall enforcement switched {} by {} (was {}). "
                        + "Repositories in QUARANTINE mode {} deny downloads that violate their policy.",
                enabled ? "ON" : "OFF",
                updatedBy == null ? "unknown" : updatedBy,
                was ? "on" : "off",
                enabled ? "now" : "no longer");
        return saved;
    }

    /**
     * Warms the cache once the context is up, so the first download after a
     * start does not pay for the read and — more importantly — so the watermark
     * is stamped before any artifact can be evaluated against it.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        refresh();
    }

    /**
     * Re-reads the row and republishes the snapshot. Never throws.
     *
     * <p>Also performs the one write this class does outside {@link #save}: when
     * enforcement is found to be on and the watermark has never been stamped
     * (an install that enabled it through the property rather than the API), it
     * is stamped now. Without that, every component in the repository would look
     * newer than "never" and the first download after enabling would block
     * artifacts that had been cached for months.
     *
     * <p>Deliberately not {@code @Transactional}: it is called from
     * {@link #current()} on the download path, where a self-invocation would
     * never reach the proxy anyway. One read and an occasional single write are
     * fine on the repository's own transactions.
     */
    public void refresh() {
        try {
            FirewallEnforcementSettingsEntity entity = repository.findById(SETTINGS_ID).orElse(null);
            boolean enabled = entity != null && entity.isConfigured()
                    ? entity.isEnabled()
                    : properties.enabled();

            Instant since = entity == null ? null : entity.getEnforcingSince();
            if (enabled && since == null && entity != null) {
                since = Instant.now();
                entity.setEnforcingSince(since);
                entity.setUpdatedAt(Instant.now());
                repository.save(entity);
                log.info("Repository firewall enforcement is on; components already stored before {} "
                        + "are audited but never blocked", since);
            }
            publish(enabled, since);
        } catch (RuntimeException e) {
            // A firewall that cannot read its own switch must not take the
            // download path with it. Keep the last snapshot and retry after the
            // interval.
            Snapshot last = snapshot.get();
            snapshot.set(new Snapshot(last.enabled(), last.enforcingSince(), System.nanoTime()));
            log.warn("Could not read the firewall enforcement switch, keeping enabled={}: {}",
                    last.enabled(), e.getMessage());
        }
    }

    private Snapshot current() {
        Snapshot cached = snapshot.get();
        if (cached.readAtNanos() != Long.MIN_VALUE
                && System.nanoTime() - cached.readAtNanos() < properties.settingsRefreshInterval().toNanos()) {
            return cached;
        }
        refresh();
        return snapshot.get();
    }

    private void publish(FirewallEnforcementSettingsEntity entity) {
        boolean enabled = entity.isConfigured() ? entity.isEnabled() : properties.enabled();
        publish(enabled, entity.getEnforcingSince());
    }

    private void publish(boolean enabled, Instant since) {
        snapshot.set(new Snapshot(enabled, since, System.nanoTime()));
    }

    /**
     * @param readAtNanos {@link Long#MIN_VALUE} for the pre-startup value, which
     *     is always considered stale so the first read goes to the database
     */
    private record Snapshot(boolean enabled, Instant enforcingSince, long readAtNanos) {}
}
