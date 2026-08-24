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

    /**
     * Exemption management, restricted to {@code nx-admin}.
     *
     * <p>Everything about an exemption except filing one: the list enumerates
     * what an organisation has decided to let past its own firewall, and
     * approving is the act that lets it past. Note that {@code /**} matches the
     * collection path itself, so a {@code GET} on the collection is covered.
     */
    public static final String FIREWALL_EXEMPTION_PATH_PATTERN = "/api/v1/firewall/exemptions/**";

    /**
     * Filing an exemption request — {@code POST} on the collection, and the one
     * verb on that prefix that is merely {@code authenticated()}.
     *
     * <p>The customer's requirement is that a developer who hits a firewall 403
     * can ask for an exemption from the block page instead of opening a ticket;
     * an exemption process that starts with a support ticket is one people route
     * around by copying the artifact somewhere else. A request is safe to hand
     * out because it changes nothing: it is created {@code REQUESTED} and lets no
     * download through until an approver — who does need the role above — acts.
     *
     * <p>Whether a non-administrator may file one at all is
     * {@code megarepo.firewall.exemption.self-service-requests}, enforced in
     * {@code FirewallExemptionController}: it is a runtime property, and a filter
     * chain that has to be rebuilt to reflect configuration is a chain that will
     * not be.
     */
    public static final String FIREWALL_EXEMPTION_REQUEST_PATH = "/api/v1/firewall/exemptions";

    /**
     * The policy editor's rule catalogue, restricted to {@code nx-admin}.
     *
     * <p>A separate constant because the endpoint sits outside
     * {@link #FIREWALL_ADMIN_PATH_PATTERN}: the path is part of the Phase 2 API
     * contract the Web UI is written against, and moving it under
     * {@code /api/v1/admin/} to inherit that rule would break the client. Stating
     * the rule here instead keeps authorization where this project keeps it —
     * without a matcher of its own the path would fall through to the plain
     * {@code /api/v1/**} rule and be readable by any logged-in user.
     *
     * <p>It carries no component data, but it is the supporting call of an
     * administrators-only editor and enumerates which controls this build
     * actually enforces, which is not a list to hand out.
     */
    public static final String FIREWALL_RULE_TYPES_PATH = "/api/v1/firewall/rule-types";

    /**
     * The administrator role, without the {@code ROLE_} prefix, as seeded by
     * {@code V2__seed_default_data.sql}. Same value as
     * {@link #FIREWALL_ADMIN_ROLE}; named separately because the firewall rules
     * and the rules below answer different questions and could in principle
     * diverge.
     */
    public static final String ADMIN_ROLE = "nx-admin";

    /**
     * User administration: create, list, modify and delete accounts, reset any
     * account's password, and — the reason this is the most severe gap of the
     * set — assign roles.
     *
     * <p>Until this matcher existed the path fell through to the blanket
     * {@code /api/v1/** -> authenticated()}. Because {@code ApiCreateUser}
     * carries a free-form role list, <em>any</em> logged-in account could
     * {@code POST} itself a second account holding {@code nx-admin}, or
     * {@code PUT} its own record and add the role in place. The seeded read-only
     * {@code nx-viewer} — which is also what an LDAP user gets when no group
     * maps to a role — was enough. Every other rule in this class, including the
     * firewall rules above, was therefore only as strong as this one omission.
     */
    public static final String USER_ADMIN_PATH_PATTERN = "/api/v1/security/users/**";

    /**
     * The self-service endpoints carved out of {@link #USER_ADMIN_PATH_PATTERN}.
     *
     * <p>{@code SecurityUserController} serves the administrative collection and
     * the caller's own profile from the same prefix, so the subtree cannot
     * simply be closed. These four are enumerated exactly rather than punched
     * out with a {@code /me/**} wildcard, so that the hole cannot widen by
     * accident: a new endpoint added under {@code /me} later is covered by the
     * admin rule until someone deliberately lists it here, which fails closed
     * and is noticed immediately.
     *
     * <p>None of them can grant a role — {@code PUT /me} binds
     * {@code ApiUpdateProfile}, which carries names and an e-mail address and no
     * role list, and the password change verifies the current password first.
     */
    public static final String OWN_PROFILE_PATH = "/api/v1/security/users/me";

    public static final String OWN_PROFILE_VERIFY_PASSWORD_PATH = "/api/v1/security/users/me/verify-password";

    public static final String OWN_PROFILE_CHANGE_PASSWORD_PATH = "/api/v1/security/users/me/change-password";

    /**
     * Role definitions: which privileges a role carries and which roles it
     * nests. Writable here means privileges can be added to a role the caller
     * already holds, which is the same escalation as
     * {@link #USER_ADMIN_PATH_PATTERN} by a different route.
     */
    public static final String ROLE_ADMIN_PATH_PATTERN = "/api/v1/security/roles/**";

    /**
     * Anonymous-access settings: whether unauthenticated repository access is on
     * at all, and which account it borrows. Pointing it at a privileged account
     * hands that account's rights to the whole internet.
     */
    public static final String ANONYMOUS_ADMIN_PATH_PATTERN = "/api/v1/security/anonymous/**";

    /**
     * LDAP server configuration — the authentication source itself. A writer can
     * point the instance at an LDAP server they control and log in as anyone, or
     * flip {@code ldapGroupsAsRoles} to map a group they own onto
     * {@code nx-admin}.
     */
    public static final String LDAP_ADMIN_PATH_PATTERN = "/api/v1/security/ldap/**";

    /**
     * The TLS truststore. Adding a CA here makes this instance trust
     * certificates that CA issues, so a writer can make a
     * machine-in-the-middle proxy look legitimate to every outbound proxy-repo
     * fetch.
     */
    public static final String SSL_ADMIN_PATH_PATTERN = "/api/v1/security/ssl/**";

    /**
     * Outbound HTTP proxy settings, which carry stored proxy credentials, and
     * license administration. A writer can route all outbound repository traffic
     * through a host of their choosing.
     *
     * <p>{@code GET /api/v1/system/license} is deliberately <b>not</b> covered;
     * see the chain for why.
     */
    public static final String SYSTEM_ADMIN_PATH_PATTERN = "/api/v1/system/**";

    /**
     * Bulk repository import/export and the Nexus migration runner. The import
     * accepts a YAML document describing repositories to create, and the
     * migration executes against a remote Nexus with supplied credentials.
     *
     * <p>Also covers {@link #FIREWALL_ADMIN_PATH_PATTERN}, which stays listed
     * separately: it is the narrower and better-documented rule, it is asserted
     * by its own test, and leaving it in place keeps that test meaningful.
     */
    public static final String ADMIN_PATH_PATTERN = "/api/v1/admin/**";

    /**
     * Read of the license banner, shown in the sidebar and on the dashboard to
     * every logged-in user.
     */
    public static final String LICENSE_PATH = "/api/v1/system/license";

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
                        // Order is the rule here: the narrow POST must be stated
                        // before the pattern that would otherwise swallow it.
                        .requestMatchers(HttpMethod.POST, FIREWALL_EXEMPTION_REQUEST_PATH)
                        .authenticated()
                        .requestMatchers(FIREWALL_EXEMPTION_PATH_PATTERN)
                        .hasRole(FIREWALL_ADMIN_ROLE)
                        .requestMatchers(FIREWALL_RULE_TYPES_PATH)
                        .hasRole(FIREWALL_ADMIN_ROLE)
                        // Same reasoning for the NVD surface, which sits under the
                        // /api/v1/security prefix instead. Both rules have to stand
                        // ahead of the blanket /api/v1/** -> authenticated() below;
                        // the first matching rule wins, so a later, broader entry
                        // never tightens an earlier one.
                        .requestMatchers(NVD_FIREWALL_PATH_PATTERN)
                        .hasRole(FIREWALL_ADMIN_ROLE)
                        // Identity and access administration. These four decide
                        // who exists, what they may do, and where the instance
                        // gets its identities from — so they gate every other
                        // rule in this class, the firewall rules above included.
                        // All of them fell through to /api/v1/** ->
                        // authenticated() before, which made the whole role
                        // model advisory: a read-only account could POST itself
                        // an nx-admin user and come back as an administrator.
                        //
                        // The self-service endpoints have to be listed first.
                        // The first matching rule wins, so these exact paths
                        // escape the admin rule that follows, while everything
                        // else under the users prefix — the collection, the
                        // per-user records, the administrative password reset —
                        // does not.
                        .requestMatchers(HttpMethod.GET, OWN_PROFILE_PATH)
                        .authenticated()
                        .requestMatchers(HttpMethod.PUT, OWN_PROFILE_PATH)
                        .authenticated()
                        .requestMatchers(HttpMethod.POST, OWN_PROFILE_VERIFY_PASSWORD_PATH)
                        .authenticated()
                        .requestMatchers(HttpMethod.PUT, OWN_PROFILE_CHANGE_PASSWORD_PATH)
                        .authenticated()
                        .requestMatchers(USER_ADMIN_PATH_PATTERN)
                        .hasRole(ADMIN_ROLE)
                        .requestMatchers(ROLE_ADMIN_PATH_PATTERN)
                        .hasRole(ADMIN_ROLE)
                        .requestMatchers(ANONYMOUS_ADMIN_PATH_PATTERN)
                        .hasRole(ADMIN_ROLE)
                        .requestMatchers(LDAP_ADMIN_PATH_PATTERN)
                        .hasRole(ADMIN_ROLE)
                        // System administration. Lower severity than the block
                        // above — none of it hands out a role directly — but
                        // each one is a way to redirect or intercept what the
                        // instance does: trust a chosen CA, route outbound
                        // traffic through a chosen proxy, rewrite the repository
                        // set from a YAML document, drive a migration against a
                        // remote Nexus.
                        .requestMatchers(SSL_ADMIN_PATH_PATTERN)
                        .hasRole(ADMIN_ROLE)
                        // Exception inside SYSTEM_ADMIN_PATH_PATTERN: the
                        // license banner is read by the sidebar and the
                        // dashboard on every page load, for every logged-in
                        // user, not just administrators. Gating the read would
                        // blank that banner for non-admins — a behavior change
                        // with no security benefit, since the payload is the
                        // edition, the licensee and a seat count, all of which
                        // the same user already sees rendered. Installing and
                        // removing a license are administrative and are covered
                        // by the rule below.
                        .requestMatchers(HttpMethod.GET, LICENSE_PATH)
                        .authenticated()
                        .requestMatchers(SYSTEM_ADMIN_PATH_PATTERN)
                        .hasRole(ADMIN_ROLE)
                        .requestMatchers(ADMIN_PATH_PATTERN)
                        .hasRole(ADMIN_ROLE)
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
