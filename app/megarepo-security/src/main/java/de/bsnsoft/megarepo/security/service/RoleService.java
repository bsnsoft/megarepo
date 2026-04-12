package de.bsnsoft.megarepo.security.service;

import de.bsnsoft.megarepo.database.entity.RoleEntity;
import de.bsnsoft.megarepo.database.repository.RoleJpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class RoleService {

    private final RoleJpaRepository roleJpaRepository;

    public RoleService(RoleJpaRepository roleJpaRepository) {
        this.roleJpaRepository = roleJpaRepository;
    }

    @Transactional
    public RoleEntity createRole(
            String id, String name, String description, Set<String> privileges, Set<String> nestedRoles) {
        if (roleJpaRepository.existsById(id)) {
            throw new IllegalArgumentException("Role already exists: " + id);
        }

        RoleEntity entity = new RoleEntity();
        entity.setId(id);
        entity.setName(name);
        entity.setDescription(description);
        entity.setPrivileges(privileges);
        entity.setNestedRoles(nestedRoles);

        Instant now = Instant.now();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);

        return roleJpaRepository.save(entity);
    }

    @Transactional
    public RoleEntity updateRole(
            String id, String name, String description, Set<String> privileges, Set<String> nestedRoles) {
        RoleEntity entity = roleJpaRepository
                .findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Role not found: " + id));

        if (entity.isReadOnly()) {
            throw new IllegalStateException("Cannot modify read-only role: " + id);
        }

        entity.setName(name);
        entity.setDescription(description);
        entity.setPrivileges(privileges);
        entity.setNestedRoles(nestedRoles);
        entity.setUpdatedAt(Instant.now());

        return roleJpaRepository.save(entity);
    }

    @Transactional
    public void deleteRole(String id) {
        RoleEntity entity = roleJpaRepository
                .findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Role not found: " + id));

        if (entity.isReadOnly()) {
            throw new IllegalStateException("Cannot delete read-only role: " + id);
        }

        roleJpaRepository.deleteById(id);
    }

    public List<RoleEntity> findAll() {
        return roleJpaRepository.findAll();
    }

    public Optional<RoleEntity> findById(String id) {
        return roleJpaRepository.findById(id);
    }
}
