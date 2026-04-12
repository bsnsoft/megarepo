package de.bsnsoft.megarepo.security.rbac;

import de.bsnsoft.megarepo.core.security.PrivilegeEvaluator;
import de.bsnsoft.megarepo.core.security.UserPrincipal;
import de.bsnsoft.megarepo.database.entity.PrivilegeEntity;
import de.bsnsoft.megarepo.database.repository.PrivilegeJpaRepository;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
public class PrivilegeEvaluatorImpl implements PrivilegeEvaluator {

    private final RoleResolver roleResolver;
    private final PrivilegeJpaRepository privilegeJpaRepository;

    public PrivilegeEvaluatorImpl(RoleResolver roleResolver, PrivilegeJpaRepository privilegeJpaRepository) {
        this.roleResolver = roleResolver;
        this.privilegeJpaRepository = privilegeJpaRepository;
    }

    @Override
    public boolean hasPermission(UserPrincipal user, String action, String format, String repositoryName) {
        Set<String> privilegeNames = roleResolver.resolvePrivileges(user.roles());

        for (String privilegeName : privilegeNames) {
            Optional<PrivilegeEntity> privilegeOpt = privilegeJpaRepository.findById(privilegeName);
            if (privilegeOpt.isEmpty()) {
                continue;
            }

            PrivilegeEntity privilege = privilegeOpt.get();
            if (matchesPrivilege(privilege, action, format, repositoryName)) {
                return true;
            }
        }

        return false;
    }

    private boolean matchesPrivilege(
            PrivilegeEntity privilege, String action, String format, String repositoryName) {
        Map<String, Object> props = privilege.getProperties();

        String privActions = getStringProperty(props, "actions");
        String privFormat = getStringProperty(props, "format");
        String privRepository = getStringProperty(props, "repository");

        if (!matchesActions(privActions, action)) {
            return false;
        }
        if (!matchesWildcard(privFormat, format)) {
            return false;
        }
        return matchesWildcard(privRepository, repositoryName);
    }

    private boolean matchesActions(String privilegeActions, String requestedAction) {
        if (privilegeActions == null || "*".equals(privilegeActions)) {
            return true;
        }
        String[] actions = privilegeActions.split(",");
        for (String a : actions) {
            if (a.trim().equals(requestedAction)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesWildcard(String pattern, String value) {
        if (pattern == null || "*".equals(pattern)) {
            return true;
        }
        return pattern.equals(value);
    }

    private String getStringProperty(Map<String, Object> props, String key) {
        Object value = props.get(key);
        return value != null ? value.toString() : null;
    }
}
