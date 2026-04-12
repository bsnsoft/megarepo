package de.bsnsoft.megarepo.security.ldap;

import de.bsnsoft.megarepo.core.exception.NotFoundException;
import de.bsnsoft.megarepo.database.entity.LdapServerEntity;
import de.bsnsoft.megarepo.database.repository.LdapServerJpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class LdapServerService {

    private final LdapServerJpaRepository ldapServerRepository;
    private final LdapAuthenticationService ldapAuthenticationService;

    public LdapServerService(
            LdapServerJpaRepository ldapServerRepository,
            LdapAuthenticationService ldapAuthenticationService) {
        this.ldapServerRepository = ldapServerRepository;
        this.ldapAuthenticationService = ldapAuthenticationService;
    }

    public List<LdapServerEntity> findAll() {
        return ldapServerRepository.findAllByOrderBySortOrder();
    }

    public LdapServerEntity findByName(String name) {
        return ldapServerRepository
                .findByName(name)
                .orElseThrow(() -> new NotFoundException("LDAP server not found: " + name));
    }

    @Transactional
    public LdapServerEntity create(LdapServerEntity entity) {
        if (ldapServerRepository.findByName(entity.getName()).isPresent()) {
            throw new IllegalArgumentException("LDAP server already exists: " + entity.getName());
        }

        Instant now = Instant.now();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);

        if (entity.getSortOrder() == 0) {
            int maxOrder = ldapServerRepository.findAllByOrderBySortOrder().stream()
                    .mapToInt(LdapServerEntity::getSortOrder)
                    .max()
                    .orElse(0);
            entity.setSortOrder(maxOrder + 1);
        }

        return ldapServerRepository.save(entity);
    }

    @Transactional
    public LdapServerEntity update(String name, LdapServerEntity updates) {
        LdapServerEntity existing = findByName(name);

        existing.setProtocol(updates.getProtocol());
        existing.setHostname(updates.getHostname());
        existing.setPort(updates.getPort());
        existing.setSearchBase(updates.getSearchBase());
        existing.setAuthScheme(updates.getAuthScheme());
        existing.setAuthUsername(updates.getAuthUsername());
        if (updates.getAuthPassword() != null && !updates.getAuthPassword().isEmpty()) {
            existing.setAuthPassword(updates.getAuthPassword());
        }
        existing.setConnectionTimeout(updates.getConnectionTimeout());
        existing.setRetryDelay(updates.getRetryDelay());
        existing.setMaxRetries(updates.getMaxRetries());
        existing.setUserBaseDn(updates.getUserBaseDn());
        existing.setUserSubtree(updates.isUserSubtree());
        existing.setUserObjectClass(updates.getUserObjectClass());
        existing.setUserIdAttribute(updates.getUserIdAttribute());
        existing.setUserNameAttribute(updates.getUserNameAttribute());
        existing.setUserEmailAttribute(updates.getUserEmailAttribute());
        existing.setLdapGroupsAsRoles(updates.isLdapGroupsAsRoles());
        existing.setGroupType(updates.getGroupType());
        existing.setGroupBaseDn(updates.getGroupBaseDn());
        existing.setGroupSubtree(updates.isGroupSubtree());
        existing.setGroupObjectClass(updates.getGroupObjectClass());
        existing.setGroupIdAttribute(updates.getGroupIdAttribute());
        existing.setGroupMemberAttribute(updates.getGroupMemberAttribute());
        existing.setGroupMemberFormat(updates.getGroupMemberFormat());
        existing.setUserMemberOfAttribute(updates.getUserMemberOfAttribute());
        existing.setEnabled(updates.isEnabled());
        existing.setUpdatedAt(Instant.now());

        return ldapServerRepository.save(existing);
    }

    @Transactional
    public void delete(String name) {
        LdapServerEntity entity = findByName(name);
        ldapServerRepository.delete(entity);
    }

    @Transactional
    public void changeOrder(List<String> orderedNames) {
        for (int i = 0; i < orderedNames.size(); i++) {
            LdapServerEntity entity = findByName(orderedNames.get(i));
            entity.setSortOrder(i + 1);
            entity.setUpdatedAt(Instant.now());
            ldapServerRepository.save(entity);
        }
    }

    public boolean verifyConnection(String name) {
        LdapServerEntity entity = findByName(name);
        return ldapAuthenticationService.verifyConnection(entity);
    }
}
