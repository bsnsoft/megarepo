package de.bsnsoft.megarepo.rest.controller;

import de.bsnsoft.megarepo.core.exception.ValidationException;
import de.bsnsoft.megarepo.database.entity.LdapServerEntity;
import de.bsnsoft.megarepo.rest.dto.security.LdapServerXO;
import de.bsnsoft.megarepo.security.ldap.LdapServerService;
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
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/security/ldap")
public class LdapController {

    private final LdapServerService ldapServerService;

    public LdapController(LdapServerService ldapServerService) {
        this.ldapServerService = ldapServerService;
    }

    @GetMapping
    public ResponseEntity<List<LdapServerXO>> list() {
        var servers = ldapServerService.findAll().stream().map(this::toXO).toList();
        return ResponseEntity.ok(servers);
    }

    @PostMapping
    public ResponseEntity<LdapServerXO> create(@Valid @RequestBody LdapServerXO request) {
        LdapServerEntity entity = toEntity(request);
        LdapServerEntity saved = ldapServerService.create(entity);
        return ResponseEntity.created(URI.create("/api/v1/security/ldap/" + saved.getName()))
                .body(toXO(saved));
    }

    @GetMapping("/{name}")
    public ResponseEntity<LdapServerXO> get(@PathVariable String name) {
        LdapServerEntity entity = ldapServerService.findByName(name);
        return ResponseEntity.ok(toXO(entity));
    }

    @PutMapping("/{name}")
    public ResponseEntity<LdapServerXO> update(@PathVariable String name, @Valid @RequestBody LdapServerXO request) {
        LdapServerEntity updates = toEntity(request);
        LdapServerEntity saved = ldapServerService.update(name, updates);
        return ResponseEntity.ok(toXO(saved));
    }

    @DeleteMapping("/{name}")
    public ResponseEntity<Void> delete(@PathVariable String name) {
        ldapServerService.delete(name);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/change-order")
    public ResponseEntity<Void> changeOrder(@RequestBody List<String> orderedNames) {
        if (orderedNames == null || orderedNames.isEmpty()) {
            throw new ValidationException("Ordered names list must not be empty");
        }
        if (orderedNames.size() > 50) {
            throw new ValidationException("Too many LDAP servers in order list (maximum 50)");
        }
        for (String name : orderedNames) {
            if (name == null || name.isBlank() || name.length() > 200) {
                throw new ValidationException("Invalid LDAP server name in order list");
            }
        }
        ldapServerService.changeOrder(orderedNames);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{name}/verify")
    public ResponseEntity<Map<String, Object>> verify(@PathVariable String name) {
        boolean success = ldapServerService.verifyConnection(name);
        return ResponseEntity.ok(Map.of("success", success, "server", name));
    }

    private LdapServerXO toXO(LdapServerEntity entity) {
        return new LdapServerXO(
                entity.getName(),
                entity.getSortOrder(),
                entity.getProtocol(),
                entity.getHostname(),
                entity.getPort(),
                entity.getSearchBase(),
                entity.getAuthScheme(),
                entity.getAuthUsername(),
                null, // Never expose password in responses
                entity.getConnectionTimeout(),
                entity.getRetryDelay(),
                entity.getMaxRetries(),
                entity.getUserBaseDn(),
                entity.isUserSubtree(),
                entity.getUserObjectClass(),
                entity.getUserIdAttribute(),
                entity.getUserNameAttribute(),
                entity.getUserEmailAttribute(),
                entity.isLdapGroupsAsRoles(),
                entity.getGroupType(),
                entity.getGroupBaseDn(),
                entity.isGroupSubtree(),
                entity.getGroupObjectClass(),
                entity.getGroupIdAttribute(),
                entity.getGroupMemberAttribute(),
                entity.getGroupMemberFormat(),
                entity.getUserMemberOfAttribute(),
                entity.isEnabled());
    }

    private LdapServerEntity toEntity(LdapServerXO xo) {
        LdapServerEntity entity = new LdapServerEntity();
        entity.setName(xo.name());
        entity.setSortOrder(xo.sortOrder());
        entity.setProtocol(xo.protocol());
        entity.setHostname(xo.hostname());
        entity.setPort(xo.port());
        entity.setSearchBase(xo.searchBase());
        entity.setAuthScheme(xo.authScheme());
        entity.setAuthUsername(xo.authUsername());
        entity.setAuthPassword(xo.authPassword());
        entity.setConnectionTimeout(xo.connectionTimeout());
        entity.setRetryDelay(xo.retryDelay());
        entity.setMaxRetries(xo.maxRetries());
        entity.setUserBaseDn(xo.userBaseDn());
        entity.setUserSubtree(xo.userSubtree());
        entity.setUserObjectClass(xo.userObjectClass());
        entity.setUserIdAttribute(xo.userIdAttribute());
        entity.setUserNameAttribute(xo.userNameAttribute());
        entity.setUserEmailAttribute(xo.userEmailAttribute());
        entity.setLdapGroupsAsRoles(xo.ldapGroupsAsRoles());
        entity.setGroupType(xo.groupType());
        entity.setGroupBaseDn(xo.groupBaseDn());
        entity.setGroupSubtree(xo.groupSubtree());
        entity.setGroupObjectClass(xo.groupObjectClass());
        entity.setGroupIdAttribute(xo.groupIdAttribute());
        entity.setGroupMemberAttribute(xo.groupMemberAttribute());
        entity.setGroupMemberFormat(xo.groupMemberFormat());
        entity.setUserMemberOfAttribute(xo.userMemberOfAttribute());
        entity.setEnabled(xo.enabled());
        return entity;
    }
}
