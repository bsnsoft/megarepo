package de.bsnsoft.megarepo.rest.controller;

import de.bsnsoft.megarepo.core.exception.NotFoundException;
import de.bsnsoft.megarepo.core.exception.ValidationException;
import de.bsnsoft.megarepo.database.entity.RoutingRuleEntity;
import de.bsnsoft.megarepo.database.repository.RoutingRuleJpaRepository;
import de.bsnsoft.megarepo.rest.dto.routing.CreateRoutingRuleRequest;
import de.bsnsoft.megarepo.rest.dto.routing.RoutingRuleXO;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/routing-rules")
public class RoutingRuleController {

    private final RoutingRuleJpaRepository routingRuleRepository;

    public RoutingRuleController(RoutingRuleJpaRepository routingRuleRepository) {
        this.routingRuleRepository = routingRuleRepository;
    }

    @GetMapping
    public ResponseEntity<List<RoutingRuleXO>> list() {
        var rules = routingRuleRepository.findAll().stream().map(this::toXO).toList();
        return ResponseEntity.ok(rules);
    }

    @GetMapping("/{name}")
    public ResponseEntity<RoutingRuleXO> get(@PathVariable String name) {
        var entity = routingRuleRepository
                .findById(name)
                .orElseThrow(() -> new NotFoundException("Routing rule not found: " + name));
        return ResponseEntity.ok(toXO(entity));
    }

    @PostMapping
    public ResponseEntity<RoutingRuleXO> create(@Valid @RequestBody CreateRoutingRuleRequest request) {
        if (routingRuleRepository.existsById(request.name())) {
            throw new ValidationException("Routing rule already exists: " + request.name());
        }
        validateMode(request.mode());

        var entity = new RoutingRuleEntity();
        entity.setName(request.name());
        entity.setDescription(request.description());
        entity.setMode(request.mode());
        entity.setMatchers(Map.of("patterns", request.matchers()));
        entity.setCreatedAt(Instant.now());

        var saved = routingRuleRepository.save(entity);
        return ResponseEntity.created(URI.create("/api/v1/routing-rules/" + saved.getName()))
                .body(toXO(saved));
    }

    @PutMapping("/{name}")
    public ResponseEntity<RoutingRuleXO> update(
            @PathVariable String name, @Valid @RequestBody CreateRoutingRuleRequest request) {
        var entity = routingRuleRepository
                .findById(name)
                .orElseThrow(() -> new NotFoundException("Routing rule not found: " + name));
        validateMode(request.mode());

        entity.setDescription(request.description());
        entity.setMode(request.mode());
        entity.setMatchers(Map.of("patterns", request.matchers()));

        var saved = routingRuleRepository.save(entity);
        return ResponseEntity.ok(toXO(saved));
    }

    @DeleteMapping("/{name}")
    public ResponseEntity<Void> delete(@PathVariable String name) {
        if (!routingRuleRepository.existsById(name)) {
            throw new NotFoundException("Routing rule not found: " + name);
        }
        routingRuleRepository.deleteById(name);
        return ResponseEntity.noContent().build();
    }

    private RoutingRuleXO toXO(RoutingRuleEntity entity) {
        @SuppressWarnings("unchecked")
        List<String> patterns = entity.getMatchers() != null && entity.getMatchers().containsKey("patterns")
                ? (List<String>) entity.getMatchers().get("patterns")
                : List.of();
        return new RoutingRuleXO(
                entity.getName(), entity.getDescription(), entity.getMode(), patterns, entity.getCreatedAt());
    }

    private void validateMode(String mode) {
        if (!"ALLOW".equals(mode) && !"BLOCK".equals(mode)) {
            throw new ValidationException("Mode must be either ALLOW or BLOCK, got: " + mode);
        }
    }
}
