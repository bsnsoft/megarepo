package de.bsnsoft.megarepo.security;

import de.bsnsoft.megarepo.security.auth.AnonymousAccessFilter;
import de.bsnsoft.megarepo.security.auth.JwtAuthenticationFilter;
import de.bsnsoft.megarepo.security.auth.UiAuthenticationEntryPoint;
import de.bsnsoft.megarepo.security.auth.ratelimit.LoginRateLimitFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.DelegatingAuthenticationEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.www.BasicAuthenticationEntryPoint;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.web.firewall.StrictHttpFirewall;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestHeaderRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.util.LinkedHashMap;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * Admin surface of the repository firewall, restricted to {@code nx-admin}.
     *
     * <p>Exposed as a constant so a controller under this prefix can assert that
     * it really is covered by the rule. Renaming an endpoint out of this prefix
     * would otherwise silently downgrade it to plain {@code authenticated()},
     * which is the failure mode that produced {@code NvdFirewallController}
     * having no authorization at all.
     */
    public static final String FIREWALL_ADMIN_PATH_PATTERN = "/api/v1/admin/firewall/**";

    /**
     * The older NVD-based firewall surface, restricted to the same {@code nx-admin}
     * as {@link #FIREWALL_ADMIN_PATH_PATTERN}.
     *
     * <p>It lives under {@code /api/v1/security/**} for historical reasons rather
     * than under the admin prefix, which is exactly how it escaped notice: no
     * matcher named it, so it fell through to the blanket
     * {@code /api/v1/** -> authenticated()} and every logged-in account could read
     * the vulnerability inventory, edit the whitelist and switch the firewall off.
     * Same data, same controls, same blast radius as the newer surface — so the
     * same role, and a separate constant so that the rule and the endpoints stay
     * visibly tied together.
     */
    public static final String NVD_FIREWALL_PATH_PATTERN = "/api/v1/security/nvd-firewall/**";

    /**
     * Role required for {@link #FIREWALL_ADMIN_PATH_PATTERN} and
     * {@link #NVD_FIREWALL_PATH_PATTERN}, without the {@code ROLE_} prefix.
     */
    public static final String FIREWALL_ADMIN_ROLE = "nx-admin";

    @Bean
    public SecurityFilterChain filterChain(
            HttpSecurity http,
            JwtAuthenticationFilter jwtFilter,
            AnonymousAccessFilter anonFilter,
            LoginRateLimitFilter rateLimitFilter,
            AuthenticationProvider ldapAwareAuthenticationProvider)
            throws Exception {
        AuthenticationEntryPoint entryPoint = authenticationEntryPoint();
        http.csrf(csrf -> csrf.ignoringRequestMatchers("/repository/**", "/api/**", "/v2/**"))
                .headers(headers -> {
                    headers.contentTypeOptions(Customizer.withDefaults()); // X-Content-Type-Options: nosniff
                    headers.frameOptions(frame -> frame.deny()); // X-Frame-Options: DENY
                    headers.httpStrictTransportSecurity(hsts -> hsts
                            .includeSubDomains(true)
                            .maxAgeInSeconds(31536000)); // Strict-Transport-Security: 1 year
                    headers.permissionsPolicy(permissions -> permissions
                            .policy("camera=(), microphone=(), geolocation=()"));
                    headers.contentSecurityPolicy(csp -> csp
                            .policyDirectives(
                                    "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; "
                                            + "img-src 'self' data:; font-src 'self'; connect-src 'self'; frame-ancestors 'none'"));
                    headers.referrerPolicy(referrer -> referrer
                            .policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter
                                    .ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN));
                })
                .authenticationProvider(ldapAwareAuthenticationProvider)
                .authorizeHttpRequests(auth -> auth.requestMatchers(
                                "/", "/index.html", "/ui/**", "/assets/**", "/favicon.ico", "/static/**")
                        .permitAll()
                        .requestMatchers(
                                "/swagger-ui.html",
                                "/swagger-ui/**",
                                "/api/v1/api-docs",
                                "/api/v1/api-docs/**",
                                "/api/v1/api-docs.yaml")
                        .permitAll()
                        .requestMatchers("/api/v1/security/auth/**")
                        .permitAll()
                        .requestMatchers("/api/v1/status")
                        .permitAll()
                        // The repository firewall's admin surface enumerates every
                        // component in every repository together with the advisories
                        // that match it — an inventory of what is worth attacking.
                        // Authentication alone, the rule the rest of /api/v1/** gets,
                        // is not enough for that; any logged-in reader would do.
                        // Authorization in this project is expressed here rather than
                        // with @PreAuthorize (method security is not enabled), so the
                        // rule belongs in the chain. Role ids reach the security
                        // context as ROLE_<id>, hence "nx-admin" from V2's seed.
                        .requestMatchers(FIREWALL_ADMIN_PATH_PATTERN)
                        .hasRole(FIREWALL_ADMIN_ROLE)
                        // Same reasoning for the NVD surface, which sits under the
                        // /api/v1/security prefix instead. Both rules have to stand
                        // ahead of the blanket /api/v1/** -> authenticated() below;
                        // the first matching rule wins, so a later, broader entry
                        // never tightens an earlier one.
                        .requestMatchers(NVD_FIREWALL_PATH_PATTERN)
                        .hasRole(FIREWALL_ADMIN_ROLE)
                        .requestMatchers("/actuator/health", "/actuator/info")
                        .permitAll()
                        .requestMatchers("/actuator/**")
                        .authenticated()
                        .requestMatchers(HttpMethod.GET, "/repository/**")
                        .permitAll()
                        .requestMatchers(HttpMethod.HEAD, "/repository/**")
                        .permitAll()
                        .requestMatchers("/repository/**")
                        .authenticated()
                        .requestMatchers(HttpMethod.GET, "/v2/")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/v2/token")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, "/v2/token")
                        .permitAll()
                        .requestMatchers("/v2/**")
                        .authenticated()
                        .requestMatchers("/api/v1/**")
                        .authenticated()
                        .anyRequest()
                        .permitAll())
                .addFilterBefore(rateLimitFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(anonFilter, UsernamePasswordAuthenticationFilter.class)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.authenticationEntryPoint(entryPoint))
                .exceptionHandling(ex -> ex.authenticationEntryPoint(entryPoint));
        return http.build();
    }

    /**
     * Split 401 challenge behavior between browser (SPA) and tooling clients.
     *
     * <p>Web-UI requests (everything under {@code /api/v1/**} plus anything sent
     * with {@code X-Requested-With: XMLHttpRequest}) get a plain 401 JSON response
     * <b>without</b> a {@code WWW-Authenticate: Basic} header. Otherwise browsers
     * would show their native Basic-Auth popup whenever a UI session token expires,
     * instead of letting the SPA redirect to its own login screen (osTicket #117649).
     *
     * <p>All other endpoints — notably {@code /repository/**} (Maven, npm, pip)
     * and {@code /v2/**} (Docker) — keep the standard Basic challenge, which
     * tooling clients rely on to know they must send credentials.
     */
    private static AuthenticationEntryPoint authenticationEntryPoint() {
        RequestMatcher uiRequestMatcher = new OrRequestMatcher(
                PathPatternRequestMatcher.withDefaults().matcher("/api/v1/**"),
                new RequestHeaderRequestMatcher("X-Requested-With", "XMLHttpRequest"));

        BasicAuthenticationEntryPoint basicEntryPoint = new BasicAuthenticationEntryPoint();
        basicEntryPoint.setRealmName("MegaRepo");

        LinkedHashMap<RequestMatcher, AuthenticationEntryPoint> entryPoints = new LinkedHashMap<>();
        entryPoints.put(uiRequestMatcher, new UiAuthenticationEntryPoint());

        DelegatingAuthenticationEntryPoint entryPoint = new DelegatingAuthenticationEntryPoint(entryPoints);
        entryPoint.setDefaultEntryPoint(basicEntryPoint);
        return entryPoint;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Allow encoded slashes (%2F) in URL paths. Required for npm scoped packages
     * which use URLs like {@code @scope%2Fname}. Tomcat's encodedSolidusHandling=decode
     * decodes %2F before it reaches the servlet, so the StrictHttpFirewall only sees
     * the decoded path. We still need to explicitly allow it for edge cases where the
     * raw URI is checked.
     */
    @Bean
    public WebSecurityCustomizer webSecurityCustomizer() {
        var firewall = new StrictHttpFirewall();
        firewall.setAllowUrlEncodedSlash(true);
        return web -> web.httpFirewall(firewall);
    }
}
