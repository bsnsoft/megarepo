package de.bsnsoft.megarepo.rest.controller;

import de.bsnsoft.megarepo.database.entity.AnonymousAccessSettingsEntity;
import de.bsnsoft.megarepo.database.entity.UserEntity;
import de.bsnsoft.megarepo.database.repository.AnonymousAccessJpaRepository;
import de.bsnsoft.megarepo.security.SecurityConfig;
import de.bsnsoft.megarepo.security.auth.AnonymousAccessFilter;
import de.bsnsoft.megarepo.security.auth.JwtAuthenticationFilter;
import de.bsnsoft.megarepo.security.auth.ratelimit.LoginRateLimitFilter;
import de.bsnsoft.megarepo.security.ldap.LdapServerService;
import de.bsnsoft.megarepo.security.service.RoleService;
import de.bsnsoft.megarepo.security.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who may administer identities: accounts, roles, anonymous access and the LDAP
 * sources users authenticate against.
 *
 * <p>This is the most consequential of the authorization tests in this module,
 * because the gap it covers made every other one moot. None of these four paths
 * was named by a matcher, so all of them fell through to the blanket
 * {@code /api/v1/** -> authenticated()}. {@code ApiCreateUser} carries a
 * free-form role list, so any logged-in account — the seeded read-only
 * {@code nx-viewer}, which is also the default for an LDAP user whose groups map
 * to nothing — could POST itself an account holding {@code nx-admin}, or PUT its
 * own record and add the role in place. Whoever could do that could then switch
 * the repository firewall off, so the rules guarding it were decoration.
 *
 * <p>Like {@link NvdFirewallAuthorizationTest} this drives the <b>real</b>
 * {@link SecurityConfig} bean and its real filter chain rather than a test-local
 * restatement of the rules. Authorization in this project lives in that one
 * class — method security is not enabled, so a {@code @PreAuthorize} would
 * authorize everyone while appearing to do the opposite — and a test that
 * rebuilt the rules would prove only that the copy agrees with itself.
 *
 * <p>Every verb each controller exposes is asserted rather than a sample, reads
 * included: the user list alone is an inventory of accounts and their roles, and
 * a matcher can cover a subtree while missing the bare collection path.
 *
 * <p>The self-service endpoints get their own test. They are the reason the
 * users prefix cannot simply be closed — {@code SecurityUserController} serves
 * the administrative collection and the caller's own profile from one
 * {@code @RequestMapping} — and an over-broad fix here would lock every
 * non-administrator out of their own password change, which is the sort of
 * breakage that gets a security fix reverted.
 *
 * <p>The three collaborating filters are constructed with null dependencies on
 * purpose; see {@link FirewallAdminAuthorizationTest} for why each one
 * short-circuits before touching them.
 */
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = {SecurityConfig.class, SecurityAdminAuthorizationTest.TestConfig.class})
class SecurityAdminAuthorizationTest {

    private static final String USERS = "/api/v1/security/users";
    private static final String USER = USERS + "/victim";
    private static final String USER_PASSWORD = USER + "/change-password";

    private static final String OWN_PROFILE = USERS + "/me";
    private static final String OWN_VERIFY_PASSWORD = OWN_PROFILE + "/verify-password";
    private static final String OWN_CHANGE_PASSWORD = OWN_PROFILE + "/change-password";

    private static final String ROLES = "/api/v1/security/roles";
    private static final String ROLE = ROLES + "/nx-admin";

    private static final String ANONYMOUS = "/api/v1/security/anonymous";

    private static final String LDAP = "/api/v1/security/ldap";
    private static final String LDAP_SERVER = LDAP + "/corp";
    private static final String LDAP_ORDER = LDAP + "/change-order";
    private static final String LDAP_VERIFY = LDAP_SERVER + "/verify";

    /** The escalation itself: a new account holding the administrator role. */
    private static final String CREATE_ADMIN_BODY =
            """
            {"userId":"backdoor","firstName":"Back","lastName":"Door","emailAddress":"b@d.example",
             "password":"hunter2hunter2","status":"ACTIVE","roles":["nx-admin"]}""";

    /** The same escalation by the other route: grant the role to an existing account. */
    private static final String PROMOTE_BODY =
            """
            {"userId":"victim","firstName":"V","lastName":"Ictim","emailAddress":"v@d.example",
             "password":"hunter2hunter2","status":"ACTIVE","roles":["nx-admin"]}""";

    private static final String ROLE_BODY =
            """
            {"id":"sneaky","name":"Sneaky","description":"","privileges":["nx-all"],"roles":[]}""";

    private static final String ANONYMOUS_BODY = """
            {"enabled":true,"userId":"admin","realmName":"local"}""";

    private static final String LDAP_BODY =
            """
            {"name":"evil","sortOrder":0,"protocol":"ldap","hostname":"attacker.example","port":389,
             "searchBase":"dc=x","authScheme":"simple","authUsername":"u","authPassword":"p",
             "connectionTimeout":30,"retryDelay":30,"maxRetries":3,"userBaseDn":"ou=u","userSubtree":true,
             "userObjectClass":"inetOrgPerson","userIdAttribute":"uid","userNameAttribute":"cn",
             "userEmailAttribute":"mail","ldapGroupsAsRoles":true,"groupType":"static","groupBaseDn":"ou=g",
             "groupSubtree":true,"groupObjectClass":"groupOfNames","groupIdAttribute":"cn",
             "groupMemberAttribute":"member","groupMemberFormat":"${dn}","userMemberOfAttribute":"memberOf",
             "enabled":true}""";

    private static final String PASSWORD_BODY = """
            {"password":"attackerchosen"}""";

    @Autowired private WebApplicationContext context;
    @Autowired private UserService userService;
    @Autowired private RoleService roleService;
    @Autowired private LdapServerService ldapServerService;
    @Autowired private AnonymousAccessJpaRepository anonymousRepo;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        // The mock beans are singletons in a Spring context that is cached
        // across test methods, so recorded interactions would otherwise carry
        // from one method into the next and make the never() assertions below
        // report a neighbour's calls instead of their own.
        reset(userService, roleService, ldapServerService, anonymousRepo);
    }

    @Test
    @DisplayName("anonymous callers get 401 on user administration, the account list included")
    void anonymousIsRejectedFromUserAdministration() throws Exception {
        mockMvc.perform(get(USERS)).andExpect(status().isUnauthorized());
        mockMvc.perform(json(post(USERS), CREATE_ADMIN_BODY)).andExpect(status().isUnauthorized());
        mockMvc.perform(json(put(USER), PROMOTE_BODY)).andExpect(status().isUnauthorized());
        mockMvc.perform(delete(USER)).andExpect(status().isUnauthorized());
        mockMvc.perform(json(put(USER_PASSWORD), PASSWORD_BODY)).andExpect(status().isUnauthorized());

        assertNoUserWasTouched();
    }

    @Test
    @DisplayName("a logged-in non-admin cannot create an admin, promote anyone, or list accounts")
    void nonAdminIsForbiddenFromUserAdministration() throws Exception {
        mockMvc.perform(get(USERS).with(reader())).andExpect(status().isForbidden());
        mockMvc.perform(json(post(USERS), CREATE_ADMIN_BODY).with(reader())).andExpect(status().isForbidden());
        mockMvc.perform(json(put(USER), PROMOTE_BODY).with(reader())).andExpect(status().isForbidden());
        mockMvc.perform(delete(USER).with(reader())).andExpect(status().isForbidden());
        mockMvc.perform(json(put(USER_PASSWORD), PASSWORD_BODY).with(reader())).andExpect(status().isForbidden());

        assertNoUserWasTouched();
    }

    @Test
    @DisplayName("a logged-in non-admin cannot read or write role definitions")
    void nonAdminIsForbiddenFromRoleAdministration() throws Exception {
        mockMvc.perform(get(ROLES)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(ROLES).with(reader())).andExpect(status().isForbidden());
        mockMvc.perform(get(ROLE).with(reader())).andExpect(status().isForbidden());
        mockMvc.perform(json(post(ROLES), ROLE_BODY).with(reader())).andExpect(status().isForbidden());
        mockMvc.perform(json(put(ROLE), ROLE_BODY).with(reader())).andExpect(status().isForbidden());
        mockMvc.perform(delete(ROLE).with(reader())).andExpect(status().isForbidden());

        verify(roleService, never()).createRole(any(), any(), any(), any(), any());
        verify(roleService, never()).updateRole(any(), any(), any(), any(), any());
        verify(roleService, never()).deleteRole(any());
    }

    @Test
    @DisplayName("a logged-in non-admin cannot read or repoint anonymous access")
    void nonAdminIsForbiddenFromAnonymousAdministration() throws Exception {
        mockMvc.perform(get(ANONYMOUS)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(ANONYMOUS).with(reader())).andExpect(status().isForbidden());
        mockMvc.perform(json(put(ANONYMOUS), ANONYMOUS_BODY).with(reader())).andExpect(status().isForbidden());

        verify(anonymousRepo, never()).save(any());
    }

    @Test
    @DisplayName("a logged-in non-admin cannot point the instance at another LDAP server")
    void nonAdminIsForbiddenFromLdapAdministration() throws Exception {
        mockMvc.perform(get(LDAP)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(LDAP).with(reader())).andExpect(status().isForbidden());
        mockMvc.perform(get(LDAP_SERVER).with(reader())).andExpect(status().isForbidden());
        mockMvc.perform(json(post(LDAP), LDAP_BODY).with(reader())).andExpect(status().isForbidden());
        mockMvc.perform(json(put(LDAP_SERVER), LDAP_BODY).with(reader())).andExpect(status().isForbidden());
        mockMvc.perform(delete(LDAP_SERVER).with(reader())).andExpect(status().isForbidden());
        mockMvc.perform(json(post(LDAP_ORDER), "[\"corp\"]").with(reader())).andExpect(status().isForbidden());
        mockMvc.perform(post(LDAP_VERIFY).with(reader())).andExpect(status().isForbidden());

        verify(ldapServerService, never()).create(any());
        verify(ldapServerService, never()).update(any(), any());
        verify(ldapServerService, never()).delete(any());
        verify(ldapServerService, never()).changeOrder(any());
        verify(ldapServerService, never()).verifyConnection(any());
    }

    /**
     * The carve-out, asserted from the side that would break: an ordinary user
     * has to keep reaching their own profile and their own password. If the
     * users prefix were closed without these four exceptions this is the test
     * that would say so, rather than a support ticket after the upgrade.
     */
    @Test
    @DisplayName("a non-admin still reaches their own profile and password")
    void nonAdminKeepsSelfService() throws Exception {
        var self = new UserEntity();
        self.setUserId("reader");
        self.setRoles(Set.of("nx-viewer"));
        when(userService.findById("reader")).thenReturn(Optional.of(self));
        when(userService.updateProfile(anyString(), any(), any(), any())).thenReturn(self);
        when(userService.verifyPassword(anyString(), anyString())).thenReturn(true);

        mockMvc.perform(get(OWN_PROFILE).with(reader())).andExpect(status().isOk());
        mockMvc.perform(json(put(OWN_PROFILE), "{\"firstName\":\"R\",\"lastName\":\"Eader\",\"emailAddress\":\"r@d.example\"}")
                        .with(reader()))
                .andExpect(status().isOk());
        mockMvc.perform(json(post(OWN_VERIFY_PASSWORD), "{\"password\":\"current\"}").with(reader()))
                .andExpect(status().isNoContent());
        mockMvc.perform(json(put(OWN_CHANGE_PASSWORD), "{\"currentPassword\":\"current\",\"password\":\"newpassword\"}")
                        .with(reader()))
                .andExpect(status().isNoContent());

        // Self-service must not reach the administrative mutators, in particular
        // not the password reset that takes an arbitrary user id.
        assertNoUserWasTouched();
    }

    /**
     * The self-service carve-out is by exact path and verb, so the neighbouring
     * administrative endpoint stays closed even though its path shares the
     * prefix. This is the difference between the rule as written and a
     * {@code /me/**} wildcard that a later endpoint could widen unnoticed.
     */
    @Test
    @DisplayName("self-service does not leak the administrative endpoints next to it")
    void selfServiceCarveOutIsNarrow() throws Exception {
        mockMvc.perform(json(put(USERS + "/me-not-really"), PROMOTE_BODY).with(reader()))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete(OWN_PROFILE).with(reader())).andExpect(status().isForbidden());
        mockMvc.perform(post(OWN_PROFILE).with(reader())).andExpect(status().isForbidden());

        assertNoUserWasTouched();
    }

    @Test
    @DisplayName("nx-admin reaches all four administrative surfaces")
    void adminIsAllowed() throws Exception {
        when(userService.findAll()).thenReturn(List.of());
        when(roleService.findAll()).thenReturn(List.of());
        when(ldapServerService.findAll()).thenReturn(List.of());
        when(anonymousRepo.findById(1)).thenReturn(Optional.of(new AnonymousAccessSettingsEntity()));

        mockMvc.perform(get(USERS).with(admin())).andExpect(status().isOk());
        mockMvc.perform(get(ROLES).with(admin())).andExpect(status().isOk());
        mockMvc.perform(get(LDAP).with(admin())).andExpect(status().isOk());
        mockMvc.perform(get(ANONYMOUS).with(admin())).andExpect(status().isOk());
    }

    /**
     * Status codes prove the request was turned away; these prove nothing slipped
     * past the chain on the way. {@code createUser} and {@code updateUser} are the
     * two that hand out roles, and {@code changePassword} is the administrative
     * reset that takes any user id — distinct from
     * {@code changePasswordWithVerification}, which self-service uses and which is
     * therefore not asserted here.
     */
    private void assertNoUserWasTouched() {
        verify(userService, never()).createUser(any(), any(), any(), any(), any(), any());
        verify(userService, never()).updateUser(any(), any(), any(), any(), any(), any());
        verify(userService, never()).deleteUser(any());
        verify(userService, never()).changePassword(any(), any());
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder json(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder, String body) {
        return builder.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static RequestPostProcessor reader() {
        return user("reader").roles("nx-viewer");
    }

    private static RequestPostProcessor admin() {
        return user("admin").roles(SecurityConfig.ADMIN_ROLE);
    }

    @Configuration
    @EnableWebMvc
    static class TestConfig {

        @Bean
        SecurityUserController securityUserController(UserService userService) {
            return new SecurityUserController(userService);
        }

        @Bean
        SecurityRoleController securityRoleController(RoleService roleService) {
            return new SecurityRoleController(roleService);
        }

        @Bean
        SecurityAnonymousController securityAnonymousController(AnonymousAccessJpaRepository repo) {
            return new SecurityAnonymousController(repo);
        }

        @Bean
        LdapController ldapController(LdapServerService ldapServerService) {
            return new LdapController(ldapServerService);
        }

        @Bean
        UserService userService() {
            return mock(UserService.class);
        }

        @Bean
        RoleService roleService() {
            return mock(RoleService.class);
        }

        @Bean
        LdapServerService ldapServerService() {
            return mock(LdapServerService.class);
        }

        @Bean
        AnonymousAccessJpaRepository anonymousAccessJpaRepository() {
            return mock(AnonymousAccessJpaRepository.class);
        }

        @Bean
        JwtAuthenticationFilter jwtAuthenticationFilter() {
            return new JwtAuthenticationFilter(null);
        }

        @Bean
        AnonymousAccessFilter anonymousAccessFilter() {
            return new AnonymousAccessFilter(null, null);
        }

        @Bean
        LoginRateLimitFilter loginRateLimitFilter() {
            return new LoginRateLimitFilter(null);
        }

        /**
         * Named to match the parameter {@code SecurityConfig.filterChain} declares.
         * Never invoked: no test here authenticates through the provider, they
         * inject an already-authenticated context.
         */
        @Bean
        AuthenticationProvider ldapAwareAuthenticationProvider() {
            return new AuthenticationProvider() {
                @Override
                public Authentication authenticate(Authentication authentication) {
                    return null;
                }

                @Override
                public boolean supports(Class<?> authentication) {
                    return false;
                }
            };
        }
    }
}
