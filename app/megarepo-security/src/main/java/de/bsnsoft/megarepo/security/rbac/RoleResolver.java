package de.bsnsoft.megarepo.security.rbac;

import de.bsnsoft.megarepo.database.entity.RoleEntity;
import de.bsnsoft.megarepo.database.repository.RoleJpaRepository;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

@Component
public class RoleResolver {

    private final RoleJpaRepository roleJpaRepository;

    public RoleResolver(RoleJpaRepository roleJpaRepository) {
        this.roleJpaRepository = roleJpaRepository;
    }

    public Set<String> resolvePrivileges(Set<String> roleIds) {
        Set<String> privileges = new HashSet<>();
        Set<String> visited = new HashSet<>();
        for (String roleId : roleIds) {
            resolveRecursively(roleId, privileges, visited);
        }
        return privileges;
    }

    private void resolveRecursively(String roleId, Set<String> privileges, Set<String> visited) {
        if (!visited.add(roleId)) {
            return;
        }

        Optional<RoleEntity> roleOpt = roleJpaRepository.findById(roleId);
        if (roleOpt.isEmpty()) {
            return;
        }

        RoleEntity role = roleOpt.get();
        privileges.addAll(role.getPrivileges());

        for (String nestedRoleId : role.getNestedRoles()) {
            resolveRecursively(nestedRoleId, privileges, visited);
        }
    }
}
