package de.bsnsoft.megarepo.rest.controller;

import de.bsnsoft.megarepo.database.entity.OutboundProxySettingsEntity;
import de.bsnsoft.megarepo.repository.proxy.OutboundProxySettingsService;
import de.bsnsoft.megarepo.security.SecurityConfig;
import de.bsnsoft.megarepo.security.auth.AnonymousAccessFilter;
import de.bsnsoft.megarepo.security.auth.JwtAuthenticationFilter;
import de.bsnsoft.megarepo.security.auth.ratelimit.LoginRateLimitFilter;
import de.bsnsoft.megarepo.security.ssl.SslCertificateService;
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
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
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
 * Who may change what the instance trusts and where it sends its traffic.
 *
 * <p>A tier below {@link SecurityAdminAuthorizationTest}: none of these hands
 * out a role directly, so none of them is escalation on its own. They are
 * interception. Adding a CA to the truststore makes this instance accept
 * certificates that CA issues, so a machine-in-the-middle in front of every
 * proxy-repository fetch looks legitimate; repointing the outbound HTTP proxy
 * routes that same traffic through a host of the caller's choosing, and the
 * settings carry stored proxy credentials. Both were reachable by any logged-in
 * account, the read-only {@code nx-viewer} included.
 *
 * <p>Drives the real {@link SecurityConfig} bean for the reasons given in
 * {@link NvdFirewallAuthorizationTest}. The other endpoints covered by the same
 * two rules — {@code /api/v1/admin/**} and the license mutations — live in
 * {@code megarepo-app} and are asserted there, against their real controllers,
 * by {@code AppAdminAuthorizationTest}.
 */
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = {SecurityConfig.class, SystemAdminAuthorizationTest.TestConfig.class})
class SystemAdminAuthorizationTest {

    private static final String SSL = "/api/v1/security/ssl";
    private static final String TRUSTSTORE = SSL + "/truststore";
    private static final String TRUSTSTORE_ENTRY = TRUSTSTORE + "/" + UUID.nameUUIDFromBytes("cert".getBytes());
    private static final String SSL_FETCH = SSL + "?host=example.com&port=443";

    private static final String HTTP_PROXY = "/api/v1/system/http-proxy";

    private static final String PEM_BODY = """
            {"pem":"-----BEGIN CERTIFICATE-----\\nMIIB\\n-----END CERTIFICATE-----"}""";

    private static final String PROXY_BODY =
            """
            {"enabled":true,"host":"attacker.example","port":3128,"username":"u","password":"p",
             "nonProxyHosts":""}""";

    @Autowired private WebApplicationContext context;
    @Autowired private SslCertificateService sslCertificateService;
    @Autowired private OutboundProxySettingsService proxyService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        // The mock beans are singletons in a Spring context that is cached
        // across test methods, so recorded interactions would otherwise carry
        // from one method into the next and make the never() assertions below
        // report a neighbour's calls instead of their own.
        reset(sslCertificateService, proxyService);
    }

    @Test
    @DisplayName("anonymous callers get 401 on the truststore and the outbound proxy")
    void anonymousIsRejected() throws Exception {
        mockMvc.perform(get(TRUSTSTORE)).andExpect(status().isUnauthorized());
        mockMvc.perform(json(post(TRUSTSTORE), PEM_BODY)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(SSL_FETCH)).andExpect(status().isUnauthorized());
        mockMvc.perform(delete(TRUSTSTORE_ENTRY)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(HTTP_PROXY)).andExpect(status().isUnauthorized());
        mockMvc.perform(json(put(HTTP_PROXY), PROXY_BODY)).andExpect(status().isUnauthorized());

        assertNothingWasChanged();
    }

    @Test
    @DisplayName("a logged-in non-admin cannot add a CA or repoint outbound traffic")
    void nonAdminIsForbidden() throws Exception {
        mockMvc.perform(get(TRUSTSTORE).with(reader())).andExpect(status().isForbidden());
        mockMvc.perform(json(post(TRUSTSTORE), PEM_BODY).with(reader())).andExpect(status().isForbidden());
        mockMvc.perform(get(SSL_FETCH).with(reader())).andExpect(status().isForbidden());
        mockMvc.perform(delete(TRUSTSTORE_ENTRY).with(reader())).andExpect(status().isForbidden());
        mockMvc.perform(get(HTTP_PROXY).with(reader())).andExpect(status().isForbidden());
        mockMvc.perform(json(put(HTTP_PROXY), PROXY_BODY).with(reader())).andExpect(status().isForbidden());

        assertNothingWasChanged();
    }

    @Test
    @DisplayName("nx-admin reaches both surfaces")
    void adminIsAllowed() throws Exception {
        when(sslCertificateService.listCertificates()).thenReturn(List.of());
        when(proxyService.load()).thenReturn(new OutboundProxySettingsEntity());

        mockMvc.perform(get(TRUSTSTORE).with(admin())).andExpect(status().isOk());
        mockMvc.perform(get(HTTP_PROXY).with(admin())).andExpect(status().isOk());
    }

    private void assertNothingWasChanged() {
        verify(sslCertificateService, never()).addCertificateFromPem(anyString());
        verify(sslCertificateService, never()).deleteCertificate(any());
        verify(sslCertificateService, never()).fetchCertificatesFromHost(anyString(), anyInt());
        verify(proxyService, never()).save(anyBoolean(), any(), anyInt(), any(), any(), any());
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
        SslCertificateController sslCertificateController(SslCertificateService service) {
            return new SslCertificateController(service);
        }

        @Bean
        HttpProxyController httpProxyController(OutboundProxySettingsService service) {
            return new HttpProxyController(service);
        }

        @Bean
        SslCertificateService sslCertificateService() {
            return mock(SslCertificateService.class);
        }

        @Bean
        OutboundProxySettingsService outboundProxySettingsService() {
            return mock(OutboundProxySettingsService.class);
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
