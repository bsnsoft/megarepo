package de.bsnsoft.megarepo.security.ldap;

import de.bsnsoft.megarepo.database.entity.LdapServerEntity;
import de.bsnsoft.megarepo.database.repository.LdapServerJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
import javax.naming.ldap.InitialLdapContext;
import javax.naming.ldap.LdapContext;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class LdapAuthenticationService {

    private static final Logger log = LoggerFactory.getLogger(LdapAuthenticationService.class);

    private final LdapServerJpaRepository ldapServerRepository;

    public LdapAuthenticationService(LdapServerJpaRepository ldapServerRepository) {
        this.ldapServerRepository = ldapServerRepository;
    }

    /**
     * Attempts authentication against all enabled LDAP servers in sort order.
     * Returns the first successful match.
     */
    public Optional<LdapUserInfo> authenticate(String username, String password) {
        List<LdapServerEntity> servers = ldapServerRepository.findAllByEnabledTrueOrderBySortOrder();
        if (servers.isEmpty()) {
            return Optional.empty();
        }

        for (LdapServerEntity server : servers) {
            try {
                Optional<LdapUserInfo> result = authenticateAgainstServer(server, username, password);
                if (result.isPresent()) {
                    return result;
                }
            } catch (Exception e) {
                log.warn("LDAP authentication failed against server '{}': {}", server.getName(), e.getMessage());
            }
        }

        return Optional.empty();
    }

    /**
     * Verifies that a connection can be established to the given LDAP server.
     */
    public boolean verifyConnection(LdapServerEntity server) {
        try {
            LdapContext ctx = createContext(server);
            ctx.close();
            return true;
        } catch (NamingException e) {
            log.warn("LDAP connection verification failed for '{}': {}", server.getName(), e.getMessage());
            return false;
        }
    }

    private Optional<LdapUserInfo> authenticateAgainstServer(
            LdapServerEntity server, String username, String password) throws NamingException {
        LdapContext ctx = null;
        try {
            ctx = createContext(server);

            String userDn = searchForUser(ctx, server, username);
            if (userDn == null) {
                return Optional.empty();
            }

            if (!verifyPasswordBind(server, userDn, password)) {
                return Optional.empty();
            }

            return Optional.of(extractUserInfo(ctx, server, username, userDn));
        } finally {
            closeQuietly(ctx);
        }
    }

    LdapContext createContext(LdapServerEntity server) throws NamingException {
        Hashtable<String, String> env = buildEnvironment(server);
        return new InitialLdapContext(env, null);
    }

    Hashtable<String, String> buildEnvironment(LdapServerEntity server) {
        Hashtable<String, String> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        env.put(Context.PROVIDER_URL, buildLdapUrl(server));
        env.put("com.sun.jndi.ldap.connect.timeout", String.valueOf(server.getConnectionTimeout() * 1000));
        env.put("com.sun.jndi.ldap.read.timeout", String.valueOf(server.getConnectionTimeout() * 1000));

        String authScheme = server.getAuthScheme();
        if ("anonymous".equals(authScheme)) {
            env.put(Context.SECURITY_AUTHENTICATION, "none");
        } else {
            env.put(Context.SECURITY_AUTHENTICATION, authScheme);
            if (server.getAuthUsername() != null) {
                env.put(Context.SECURITY_PRINCIPAL, server.getAuthUsername());
            }
            if (server.getAuthPassword() != null) {
                env.put(Context.SECURITY_CREDENTIALS, server.getAuthPassword());
            }
        }

        if ("ldaps".equals(server.getProtocol())) {
            env.put(Context.SECURITY_PROTOCOL, "ssl");
        }

        return env;
    }

    private String buildLdapUrl(LdapServerEntity server) {
        return server.getProtocol() + "://" + server.getHostname() + ":" + server.getPort();
    }

    String searchForUser(LdapContext ctx, LdapServerEntity server, String username) throws NamingException {
        String searchBase = server.getUserBaseDn() != null ? server.getUserBaseDn() : server.getSearchBase();
        String filter = "(&(objectClass=" + server.getUserObjectClass() + ")("
                + server.getUserIdAttribute() + "=" + escapeLdapFilter(username) + "))";

        SearchControls controls = new SearchControls();
        controls.setSearchScope(
                server.isUserSubtree() ? SearchControls.SUBTREE_SCOPE : SearchControls.ONELEVEL_SCOPE);
        controls.setReturningAttributes(new String[] {
            server.getUserIdAttribute(),
            server.getUserNameAttribute(),
            server.getUserEmailAttribute(),
            server.getUserMemberOfAttribute()
        });
        controls.setCountLimit(1);

        NamingEnumeration<SearchResult> results = ctx.search(searchBase, filter, controls);
        if (results.hasMore()) {
            SearchResult result = results.next();
            return result.getNameInNamespace();
        }
        return null;
    }

    private boolean verifyPasswordBind(LdapServerEntity server, String userDn, String password) {
        Hashtable<String, String> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        env.put(Context.PROVIDER_URL, buildLdapUrl(server));
        env.put(Context.SECURITY_AUTHENTICATION, "simple");
        env.put(Context.SECURITY_PRINCIPAL, userDn);
        env.put(Context.SECURITY_CREDENTIALS, password);
        env.put("com.sun.jndi.ldap.connect.timeout", String.valueOf(server.getConnectionTimeout() * 1000));
        env.put("com.sun.jndi.ldap.read.timeout", String.valueOf(server.getConnectionTimeout() * 1000));

        if ("ldaps".equals(server.getProtocol())) {
            env.put(Context.SECURITY_PROTOCOL, "ssl");
        }

        try {
            LdapContext verifyCtx = new InitialLdapContext(env, null);
            verifyCtx.close();
            return true;
        } catch (NamingException e) {
            log.debug("Password bind verification failed for DN '{}': {}", userDn, e.getMessage());
            return false;
        }
    }

    LdapUserInfo extractUserInfo(LdapContext ctx, LdapServerEntity server, String username, String userDn)
            throws NamingException {
        Attributes attrs = ctx.getAttributes(userDn, new String[] {
            server.getUserNameAttribute(),
            server.getUserEmailAttribute(),
            server.getUserMemberOfAttribute()
        });

        String displayName = getAttributeValue(attrs, server.getUserNameAttribute(), username);
        String email = getAttributeValue(attrs, server.getUserEmailAttribute(), "");

        Set<String> groups = new HashSet<>();
        if (server.isLdapGroupsAsRoles()) {
            groups = extractGroups(ctx, server, username, userDn, attrs);
        }

        return new LdapUserInfo(username, displayName, email, groups);
    }

    private Set<String> extractGroups(
            LdapContext ctx,
            LdapServerEntity server,
            String username,
            String userDn,
            Attributes userAttrs)
            throws NamingException {
        Set<String> groups = new HashSet<>();

        if ("dynamic".equals(server.getGroupType())) {
            Attribute memberOfAttr = userAttrs.get(server.getUserMemberOfAttribute());
            if (memberOfAttr != null) {
                for (int i = 0; i < memberOfAttr.size(); i++) {
                    String groupDn = memberOfAttr.get(i).toString();
                    String groupName = extractCnFromDn(groupDn);
                    if (groupName != null) {
                        groups.add(groupName);
                    }
                }
            }
        } else if ("static".equals(server.getGroupType())) {
            groups.addAll(searchStaticGroups(ctx, server, username, userDn));
        }

        return groups;
    }

    private Set<String> searchStaticGroups(
            LdapContext ctx, LdapServerEntity server, String username, String userDn) throws NamingException {
        Set<String> groups = new HashSet<>();
        String groupSearchBase =
                server.getGroupBaseDn() != null ? server.getGroupBaseDn() : server.getSearchBase();

        String memberValue = server.getGroupMemberFormat()
                .replace("${username}", username)
                .replace("${dn}", userDn);

        String filter = "(&(objectClass=" + server.getGroupObjectClass() + ")("
                + server.getGroupMemberAttribute() + "=" + escapeLdapFilter(memberValue) + "))";

        SearchControls controls = new SearchControls();
        controls.setSearchScope(
                server.isGroupSubtree() ? SearchControls.SUBTREE_SCOPE : SearchControls.ONELEVEL_SCOPE);
        controls.setReturningAttributes(new String[] {server.getGroupIdAttribute()});

        NamingEnumeration<SearchResult> results = ctx.search(groupSearchBase, filter, controls);
        while (results.hasMore()) {
            SearchResult result = results.next();
            Attribute groupIdAttr = result.getAttributes().get(server.getGroupIdAttribute());
            if (groupIdAttr != null) {
                groups.add(groupIdAttr.get().toString());
            }
        }

        return groups;
    }

    private String getAttributeValue(Attributes attrs, String attrName, String defaultValue)
            throws NamingException {
        Attribute attr = attrs.get(attrName);
        if (attr != null && attr.size() > 0) {
            return attr.get().toString();
        }
        return defaultValue;
    }

    private String extractCnFromDn(String dn) {
        if (dn == null || dn.isEmpty()) {
            return null;
        }
        for (String part : dn.split(",")) {
            String trimmed = part.trim();
            if (trimmed.toLowerCase().startsWith("cn=")) {
                return trimmed.substring(3);
            }
        }
        return dn;
    }

    static String escapeLdapFilter(String input) {
        if (input == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(input.length());
        for (char c : input.toCharArray()) {
            switch (c) {
                case '\\' -> sb.append("\\5c");
                case '*' -> sb.append("\\2a");
                case '(' -> sb.append("\\28");
                case ')' -> sb.append("\\29");
                case '\0' -> sb.append("\\00");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private void closeQuietly(LdapContext ctx) {
        if (ctx != null) {
            try {
                ctx.close();
            } catch (NamingException e) {
                log.debug("Error closing LDAP context", e);
            }
        }
    }
}
