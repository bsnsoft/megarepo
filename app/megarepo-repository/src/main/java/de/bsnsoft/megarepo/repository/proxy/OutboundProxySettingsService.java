package de.bsnsoft.megarepo.repository.proxy;

import de.bsnsoft.megarepo.database.entity.OutboundProxySettingsEntity;
import de.bsnsoft.megarepo.database.repository.OutboundProxySettingsJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;

/**
 * Resolves and applies the <em>effective</em> outbound (egress) proxy configuration.
 *
 * <p>The effective configuration is layered:
 * <ol>
 *   <li>If the singleton {@code outbound_proxy_settings} row has
 *       {@code configured = true}, the UI-managed values
 *       (<em>System → HTTP</em>) take precedence.</li>
 *   <li>Otherwise the deployment-side {@link OutboundProxyProperties}
 *       ({@code megarepo.outbound-proxy.*} via Helm/env) apply unchanged.</li>
 * </ol>
 *
 * <p>On application startup and on every settings change the resolved configuration
 * is pushed into {@link RemoteHttpClient#applyRuntimeConfig(OutboundProxyProperties)},
 * so a UI change takes effect without a restart.
 *
 * <p>The proxy password follows the project's existing secret convention: it is never
 * returned to clients, and a blank/absent password on update means "keep the stored
 * one" (write-only field).
 */
@Service
public class OutboundProxySettingsService {

    private static final Logger log = LoggerFactory.getLogger(OutboundProxySettingsService.class);
    private static final Integer SETTINGS_ID = 1;

    private final OutboundProxySettingsJpaRepository repository;
    private final OutboundProxyProperties fallback;
    private final RemoteHttpClient remoteHttpClient;

    public OutboundProxySettingsService(
            OutboundProxySettingsJpaRepository repository,
            OutboundProxyProperties fallback,
            RemoteHttpClient remoteHttpClient) {
        this.repository = repository;
        this.fallback = fallback;
        this.remoteHttpClient = remoteHttpClient;
    }

    /**
     * Once the application context is ready (DB available, Flyway migrated), resolve
     * the persisted settings and apply them so the upstream client reflects any
     * UI-managed proxy configuration from the moment fetches can occur.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void applyOnStartup() {
        try {
            remoteHttpClient.applyRuntimeConfig(effectiveConfig());
        } catch (RuntimeException e) {
            // Never let a proxy-config resolution problem stop the app from starting;
            // the constructor already built a client from the deployment-side fallback.
            log.warn("Could not apply runtime outbound-proxy configuration at startup, "
                    + "keeping deployment-side defaults: {}", e.getMessage());
        }
    }

    /**
     * Returns the persisted settings, creating the seeded default row if absent.
     */
    @Transactional
    public OutboundProxySettingsEntity load() {
        return repository.findById(SETTINGS_ID).orElseGet(() -> {
            OutboundProxySettingsEntity fresh = new OutboundProxySettingsEntity();
            return repository.save(fresh);
        });
    }

    /**
     * Persists the given settings and applies the resulting effective configuration
     * to the live upstream client.
     *
     * <p>Write-only password handling: when {@code newPassword} is {@code null} or
     * blank, the previously stored password is retained.
     */
    @Transactional
    public OutboundProxySettingsEntity save(
            boolean enabled, String host, int port, String username,
            String newPassword, String nonProxyHosts) {

        OutboundProxySettingsEntity entity =
                repository.findById(SETTINGS_ID).orElseGet(OutboundProxySettingsEntity::new);
        entity.setConfigured(true);
        entity.setEnabled(enabled);
        entity.setHost(blankToNull(host));
        entity.setPort(port);
        entity.setUsername(blankToNull(username));
        if (newPassword != null && !newPassword.isBlank()) {
            entity.setPassword(newPassword);
        }
        entity.setNonProxyHosts(blankToNull(nonProxyHosts));

        OutboundProxySettingsEntity saved = repository.save(entity);
        remoteHttpClient.applyRuntimeConfig(toProperties(saved));
        log.info("Outbound proxy configuration updated via UI (enabled={}, host={}, port={})",
                saved.isEnabled(), saved.getHost(), saved.getPort());
        return saved;
    }

    /**
     * Resolves the effective configuration following the layering rules.
     */
    @Transactional(readOnly = true)
    public OutboundProxyProperties effectiveConfig() {
        OutboundProxySettingsEntity entity = repository.findById(SETTINGS_ID).orElse(null);
        if (entity == null || !entity.isConfigured()) {
            return fallback;
        }
        return toProperties(entity);
    }

    /**
     * The deployment-side fallback configuration ({@code megarepo.outbound-proxy.*}).
     */
    public OutboundProxyProperties fallback() {
        return fallback;
    }

    private static OutboundProxyProperties toProperties(OutboundProxySettingsEntity e) {
        return new OutboundProxyProperties(
                e.isEnabled(),
                e.getHost(),
                e.getPort(),
                e.getUsername(),
                e.getPassword(),
                parseNonProxyHosts(e.getNonProxyHosts()));
    }

    static List<String> parseNonProxyHosts(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
