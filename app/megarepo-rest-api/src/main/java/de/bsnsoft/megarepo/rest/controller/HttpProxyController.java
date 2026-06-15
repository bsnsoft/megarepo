package de.bsnsoft.megarepo.rest.controller;

import de.bsnsoft.megarepo.database.entity.OutboundProxySettingsEntity;
import de.bsnsoft.megarepo.repository.proxy.OutboundProxySettingsService;
import de.bsnsoft.megarepo.rest.dto.system.OutboundProxySettingsXO;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Web-UI configuration of the global outbound (egress) HTTP proxy
 * (<em>System → HTTP</em>).
 *
 * <p>Persists the proxy settings and pushes them into the live upstream HTTP
 * client so changes take effect without a restart. When the UI has never been
 * used ({@code configured = false}), the deployment-side
 * {@code megarepo.outbound-proxy.*} (Helm/env) configuration remains
 * authoritative.
 *
 * <p>The proxy password is write-only: it is never returned in responses
 * ({@code passwordSet} indicates whether one is stored), and a blank password on
 * update keeps the stored one.
 */
@RestController
@RequestMapping("/api/v1/system/http-proxy")
public class HttpProxyController {

    private final OutboundProxySettingsService service;

    public HttpProxyController(OutboundProxySettingsService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<OutboundProxySettingsXO> get() {
        OutboundProxySettingsEntity entity = service.load();
        return ResponseEntity.ok(toXO(entity));
    }

    @PutMapping
    public ResponseEntity<OutboundProxySettingsXO> update(
            @Valid @RequestBody OutboundProxySettingsXO request) {
        OutboundProxySettingsEntity saved = service.save(
                request.enabled(),
                request.host(),
                request.port(),
                request.username(),
                request.password(),
                request.nonProxyHosts());
        return ResponseEntity.ok(toXO(saved));
    }

    private OutboundProxySettingsXO toXO(OutboundProxySettingsEntity e) {
        boolean passwordSet = e.getPassword() != null && !e.getPassword().isBlank();
        return new OutboundProxySettingsXO(
                e.isEnabled(),
                e.getHost(),
                e.getPort() <= 0 ? 3128 : e.getPort(),
                e.getUsername(),
                null, // write-only: never expose the stored password
                passwordSet,
                e.getNonProxyHosts(),
                e.isConfigured(),
                e.isConfigured() ? "database" : "environment");
    }
}
