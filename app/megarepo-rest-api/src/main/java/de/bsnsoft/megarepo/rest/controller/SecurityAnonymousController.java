package de.bsnsoft.megarepo.rest.controller;

import de.bsnsoft.megarepo.database.entity.AnonymousAccessSettingsEntity;
import de.bsnsoft.megarepo.database.repository.AnonymousAccessJpaRepository;
import de.bsnsoft.megarepo.rest.dto.security.AnonymousAccessSettingsXO;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/security/anonymous")
public class SecurityAnonymousController {

    private static final Integer SETTINGS_ID = 1;

    private final AnonymousAccessJpaRepository anonymousAccessJpaRepository;

    public SecurityAnonymousController(AnonymousAccessJpaRepository anonymousAccessJpaRepository) {
        this.anonymousAccessJpaRepository = anonymousAccessJpaRepository;
    }

    @GetMapping
    public ResponseEntity<AnonymousAccessSettingsXO> get() {
        var entity = anonymousAccessJpaRepository.findById(SETTINGS_ID).orElseGet(() -> {
            var defaults = new AnonymousAccessSettingsEntity();
            return anonymousAccessJpaRepository.save(defaults);
        });
        return ResponseEntity.ok(toXO(entity));
    }

    @PutMapping
    public ResponseEntity<AnonymousAccessSettingsXO> update(
            @Valid @RequestBody AnonymousAccessSettingsXO request) {
        var entity = anonymousAccessJpaRepository.findById(SETTINGS_ID).orElseGet(AnonymousAccessSettingsEntity::new);
        entity.setEnabled(request.enabled());
        entity.setUserId(request.userId());
        entity.setRealmName(request.realmName());
        var saved = anonymousAccessJpaRepository.save(entity);
        return ResponseEntity.ok(toXO(saved));
    }

    private AnonymousAccessSettingsXO toXO(AnonymousAccessSettingsEntity entity) {
        return new AnonymousAccessSettingsXO(entity.isEnabled(), entity.getUserId(), entity.getRealmName());
    }
}
