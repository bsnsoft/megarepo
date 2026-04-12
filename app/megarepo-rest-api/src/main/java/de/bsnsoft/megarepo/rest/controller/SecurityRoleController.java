package de.bsnsoft.megarepo.rest.controller;

import de.bsnsoft.megarepo.core.exception.NotFoundException;
import de.bsnsoft.megarepo.database.entity.RoleEntity;
import de.bsnsoft.megarepo.rest.dto.security.CreateRoleRequest;
import de.bsnsoft.megarepo.rest.dto.security.RoleXO;
import de.bsnsoft.megarepo.security.service.RoleService;
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
import java.util.HashSet;
import java.util.List;

@RestController
@RequestMapping("/api/v1/security/roles")
public class SecurityRoleController {

    private final RoleService roleService;

    public SecurityRoleController(RoleService roleService) {
        this.roleService = roleService;
    }

    @GetMapping
    public ResponseEntity<List<RoleXO>> list() {
        var roles = roleService.findAll().stream().map(this::toXO).toList();
        return ResponseEntity.ok(roles);
    }

    @PostMapping
    public ResponseEntity<RoleXO> create(@Valid @RequestBody CreateRoleRequest request) {
        var entity = roleService.createRole(
                request.id(),
                request.name(),
                request.description(),
                new HashSet<>(request.privileges()),
                new HashSet<>(request.roles()));
        return ResponseEntity.created(URI.create("/api/v1/security/roles/" + entity.getId()))
                .body(toXO(entity));
    }

    @GetMapping("/{id}")
    public ResponseEntity<RoleXO> get(@PathVariable String id) {
        var entity = roleService
                .findById(id)
                .orElseThrow(() -> new NotFoundException("Role not found: " + id));
        return ResponseEntity.ok(toXO(entity));
    }

    @PutMapping("/{id}")
    public ResponseEntity<RoleXO> update(@PathVariable String id, @Valid @RequestBody CreateRoleRequest request) {
        var entity = roleService.updateRole(
                id, request.name(), request.description(), new HashSet<>(request.privileges()), new HashSet<>(request.roles()));
        return ResponseEntity.ok(toXO(entity));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        roleService.deleteRole(id);
        return ResponseEntity.noContent().build();
    }

    private RoleXO toXO(RoleEntity entity) {
        return new RoleXO(
                entity.getId(),
                entity.getName(),
                entity.getDescription(),
                entity.getSource(),
                entity.isReadOnly(),
                List.copyOf(entity.getPrivileges()),
                List.copyOf(entity.getNestedRoles()));
    }
}
