package de.bsnsoft.megarepo.security.ldap;

import de.bsnsoft.megarepo.database.entity.LdapServerEntity;
import de.bsnsoft.megarepo.database.repository.LdapServerJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attributes;
import javax.naming.directory.BasicAttribute;
import javax.naming.directory.BasicAttributes;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
import javax.naming.ldap.LdapContext;
import java.time.Instant;
import java.util.Hashtable;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LdapAuthenticationServiceTest {

    @Mock
    private LdapServerJpaRepository ldapServerRepository;

    private LdapAuthenticationService service;

    @BeforeEach
    void setUp() {
        service = new LdapAuthenticationService(ldapServerRepository);
    }

    @Test
    void authenticate_returnsEmpty_whenNoServersConfigured() {
        when(ldapServerRepository.findAllByEnabledTrueOrderBySortOrder()).thenReturn(List.of());

        Optional<LdapUserInfo> result = service.authenticate("user", "pass");

        assertTrue(result.isEmpty());
    }

    @Test
    void authenticate_returnsEmpty_whenServerConnectionFails() {
        LdapServerEntity server = createTestServer("test-ldap");
        when(ldapServerRepository.findAllByEnabledTrueOrderBySortOrder()).thenReturn(List.of(server));

        // Real LDAP server won't be available, so connection will fail
        Optional<LdapUserInfo> result = service.authenticate("user", "pass");

        assertTrue(result.isEmpty());
    }

    @Test
    void authenticate_triesMultipleServers_whenFirstFails() {
        LdapServerEntity server1 = createTestServer("ldap-1");
        server1.setSortOrder(1);
        LdapServerEntity server2 = createTestServer("ldap-2");
        server2.setSortOrder(2);

        when(ldapServerRepository.findAllByEnabledTrueOrderBySortOrder()).thenReturn(List.of(server1, server2));

        // Both will fail since no real LDAP servers are running
        Optional<LdapUserInfo> result = service.authenticate("testuser", "password");

        assertTrue(result.isEmpty());
    }

    @Test
    void verifyConnection_returnsFalse_whenConnectionFails() {
        LdapServerEntity server = createTestServer("test-ldap");

        boolean result = service.verifyConnection(server);

        assertFalse(result);
    }

    @Test
    void buildEnvironment_setsCorrectProperties_forSimpleAuth() {
        LdapServerEntity server = createTestServer("test-ldap");
        server.setAuthScheme("simple");
        server.setAuthUsername("cn=admin,dc=example,dc=com");
        server.setAuthPassword("secret");

        Hashtable<String, String> env = service.buildEnvironment(server);

        assertEquals("com.sun.jndi.ldap.LdapCtxFactory", env.get("java.naming.factory.initial"));
        assertEquals("ldap://ldap.example.com:389", env.get("java.naming.provider.url"));
        assertEquals("simple", env.get("java.naming.security.authentication"));
        assertEquals("cn=admin,dc=example,dc=com", env.get("java.naming.security.principal"));
        assertEquals("secret", env.get("java.naming.security.credentials"));
    }

    @Test
    void buildEnvironment_setsAnonymousAuth() {
        LdapServerEntity server = createTestServer("test-ldap");
        server.setAuthScheme("anonymous");

        Hashtable<String, String> env = service.buildEnvironment(server);

        assertEquals("none", env.get("java.naming.security.authentication"));
    }

    @Test
    void buildEnvironment_enablesSsl_forLdaps() {
        LdapServerEntity server = createTestServer("test-ldap");
        server.setProtocol("ldaps");
        server.setPort(636);

        Hashtable<String, String> env = service.buildEnvironment(server);

        assertEquals("ldaps://ldap.example.com:636", env.get("java.naming.provider.url"));
        assertEquals("ssl", env.get("java.naming.security.protocol"));
    }

    @Test
    void buildEnvironment_setsConnectionTimeout() {
        LdapServerEntity server = createTestServer("test-ldap");
        server.setConnectionTimeout(15);

        Hashtable<String, String> env = service.buildEnvironment(server);

        assertEquals("15000", env.get("com.sun.jndi.ldap.connect.timeout"));
        assertEquals("15000", env.get("com.sun.jndi.ldap.read.timeout"));
    }

    @Test
    void escapeLdapFilter_escapesSpecialCharacters() {
        assertEquals("user\\28test\\29", LdapAuthenticationService.escapeLdapFilter("user(test)"));
        assertEquals("user\\2atest", LdapAuthenticationService.escapeLdapFilter("user*test"));
        assertEquals("user\\5ctest", LdapAuthenticationService.escapeLdapFilter("user\\test"));
        assertEquals("normaluser", LdapAuthenticationService.escapeLdapFilter("normaluser"));
    }

    @Test
    void escapeLdapFilter_returnsNull_forNullInput() {
        assertNull(LdapAuthenticationService.escapeLdapFilter(null));
    }

    @Test
    @SuppressWarnings("unchecked")
    void extractUserInfo_extractsGroupsFromMemberOf() throws NamingException {
        LdapServerEntity server = createTestServer("test-ldap");
        server.setLdapGroupsAsRoles(true);
        server.setGroupType("dynamic");

        LdapContext mockCtx = mock(LdapContext.class);

        BasicAttribute memberOfAttr = new BasicAttribute("memberOf");
        memberOfAttr.add("cn=developers,ou=groups,dc=example,dc=com");
        memberOfAttr.add("cn=admins,ou=groups,dc=example,dc=com");

        BasicAttributes attrs = new BasicAttributes();
        attrs.put(new BasicAttribute("cn", "Test User"));
        attrs.put(new BasicAttribute("mail", "test@example.com"));
        attrs.put(memberOfAttr);

        when(mockCtx.getAttributes(anyString(), any(String[].class))).thenReturn(attrs);

        LdapUserInfo info = service.extractUserInfo(
                mockCtx, server, "testuser", "uid=testuser,ou=users,dc=example,dc=com");

        assertEquals("testuser", info.username());
        assertEquals("Test User", info.displayName());
        assertEquals("test@example.com", info.email());
        assertTrue(info.groups().contains("developers"));
        assertTrue(info.groups().contains("admins"));
        assertEquals(2, info.groups().size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchForUser_buildsCorrectFilter() throws NamingException {
        LdapServerEntity server = createTestServer("test-ldap");
        server.setUserBaseDn("ou=users,dc=example,dc=com");
        server.setUserObjectClass("inetOrgPerson");
        server.setUserIdAttribute("uid");

        LdapContext mockCtx = mock(LdapContext.class);
        NamingEnumeration<SearchResult> mockResults = mock(NamingEnumeration.class);
        when(mockResults.hasMore()).thenReturn(false);
        when(mockCtx.search(eq("ou=users,dc=example,dc=com"), anyString(), any(SearchControls.class)))
                .thenReturn(mockResults);

        String result = service.searchForUser(mockCtx, server, "testuser");

        assertNull(result);
    }

    private static LdapServerEntity createTestServer(String name) {
        LdapServerEntity entity = new LdapServerEntity();
        entity.setName(name);
        entity.setSortOrder(1);
        entity.setProtocol("ldap");
        entity.setHostname("ldap.example.com");
        entity.setPort(389);
        entity.setSearchBase("dc=example,dc=com");
        entity.setAuthScheme("simple");
        entity.setAuthUsername("cn=admin,dc=example,dc=com");
        entity.setAuthPassword("adminpassword");
        entity.setConnectionTimeout(30);
        entity.setRetryDelay(300);
        entity.setMaxRetries(3);
        entity.setUserBaseDn("ou=users,dc=example,dc=com");
        entity.setUserSubtree(true);
        entity.setUserObjectClass("inetOrgPerson");
        entity.setUserIdAttribute("uid");
        entity.setUserNameAttribute("cn");
        entity.setUserEmailAttribute("mail");
        entity.setLdapGroupsAsRoles(true);
        entity.setGroupType("dynamic");
        entity.setGroupBaseDn("ou=groups,dc=example,dc=com");
        entity.setGroupSubtree(true);
        entity.setGroupObjectClass("groupOfNames");
        entity.setGroupIdAttribute("cn");
        entity.setGroupMemberAttribute("member");
        entity.setGroupMemberFormat("uid=${username},${dn}");
        entity.setUserMemberOfAttribute("memberOf");
        entity.setEnabled(true);
        entity.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        entity.setUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        return entity;
    }
}
