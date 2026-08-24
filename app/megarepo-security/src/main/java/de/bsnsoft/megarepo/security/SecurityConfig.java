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

    // ── Operational surfaces ────────────────────────────────────────────
    //
    // The third group of endpoints that never named itself in a matcher and
    // therefore ran on /api/v1/** -> authenticated(). Unlike the two groups
    // above these do not hand out privileges or redirect the instance's trust;
    // they operate on the artifacts and on the storage underneath them. The
    // read-only nx-viewer could start a cleanup task, drop a blob store, or
    // delete a repository outright.
    //
    // Each constant below is deliberately narrow, because this group is the one
    // where over-reach breaks a customer. Two facts decided every line:
    //
    //   * the documented CI identity is NOT an administrator. admin-guide.md
    //     §4 creates a user "deployer" (firstName "CI") holding nx-anonymous,
    //     i.e. browse + read. Whatever that account does today it must keep
    //     doing: format-native publish under /repository/**, the documented
    //     POST /api/v1/components/upload, and the browse and search reads.
    //   * every administrative recipe in docs/ and test-projects/ authenticates
    //     as admin/admin123 (setup.sh, test-all.sh, test-docker-api.sh,
    //     test-upgrade.sh, the k6 scripts, admin-guide.md, migration-from-nexus.md).
    //     Nothing scripted reaches the paths closed here with a lesser account.
    //
    // What is NOT listed here is as deliberate as what is; see the chain.

    /**
     * Scheduled tasks: the list, the schedule, and the run/stop triggers.
     *
     * <p>{@code POST /{id}/run} is the reason this is a write surface even
     * though it carries no body: the seeded task set includes
     * {@code repository.cleanup} and {@code blobstore.compact}
     * ({@code V2__seed_default_data.sql}), so triggering an existing task is
     * enough to start deleting artifacts. The reads go with it — the task list
     * is the instance's maintenance schedule, and there is no caller for it
     * outside the {@code /admin/tasks} page.
     */
    public static final String TASK_ADMIN_PATH_PATTERN = "/api/v1/tasks/**";

    /**
     * Blob stores — the storage backends themselves.
     *
     * <p>The read is included for a reason of its own: {@code BlobStoreXO}
     * carries the raw {@code config} map, and for an S3 store that map holds
     * {@code accessKeyId} and {@code secretAccessKey} as stored. Listing blob
     * stores therefore hands out the bucket credentials in clear text, which is
     * the same finding as the NVD firewall's API key and wants the same answer.
     * {@code DELETE} drops the store while its assets stay in the database,
     * which orphans every artifact held in it.
     */
    public static final String BLOB_STORE_ADMIN_PATH_PATTERN = "/api/v1/blobstores/**";

    /**
     * Cleanup policies: the rules that decide which artifacts a cleanup task
     * removes. Rewriting a policy is a delayed delete — the caller does not
     * remove anything, the next scheduled {@code repository.cleanup} run does.
     */
    public static final String CLEANUP_POLICY_ADMIN_PATH_PATTERN = "/api/v1/cleanup-policies/**";

    /**
     * Routing rules, which decide whether a request is allowed to reach a
     * repository's upstream at all. A writer can block or divert resolution for
     * chosen coordinate patterns.
     */
    public static final String ROUTING_RULE_ADMIN_PATH_PATTERN = "/api/v1/routing-rules/**";

    /**
     * The audit log, and its CSV/JSON export of up to 10,000 rows.
     *
     * <p>Gated for the payload rather than for any write: every row carries
     * {@code userId}, {@code ipAddress} and the full artifact {@code path}, so
     * the export is a record of who fetched what, when, from where — personal
     * data about the customer's own developers, in a file. The only consumer is
     * the {@code /admin/audit} page, which the sidebar already files under
     * Administration.
     */
    public static final String AUDIT_PATH_PATTERN = "/api/v1/audit/**";

    /**
     * The live activity feed: an SSE stream of every upload and download, plus
     * a {@code /recent} read.
     *
     * <p>Same payload as {@link #AUDIT_PATH_PATTERN} — {@code /recent} is
     * literally the audit log's first page through the same DTO — so it gets
     * the same answer; leaving it open would have made the audit rule a
     * formality. Nothing in the web UI subscribes to either endpoint (there is
     * no {@code EventSource} in the frontend at all), so this closes a surface
     * that has no client rather than one in use.
     */
    public static final String ACTIVITY_PATH_PATTERN = "/api/v1/activity/**";

    /**
     * Proxy cache administration, nested under a repository.
     *
     * <p>{@code POST /invalidate/pattern} takes a regular expression and
     * {@code .*} matches everything, so one call empties a proxy's cache and
     * sends every subsequent build back to the upstream — a denial of service
     * against the build farm, and against an air-gapped installation a
     * permanent loss of the only copy.
     *
     * <p>Listed ahead of the repository rules below because it is the narrower
     * path; the first matching rule wins.
     */
    public static final String REPOSITORY_CACHE_PATH_PATTERN = "/api/v1/repositories/*/cache/**";

    /**
     * Per-repository blacklists, nested the same way.
     *
     * <p>{@code PUT} replaces the whole pattern list, so a single call removes
     * whatever supply-chain blocking the administrator configured, silently and
     * without touching any repository setting that the UI would show.
     */
    public static final String REPOSITORY_BLACKLIST_PATH_PATTERN = "/api/v1/repositories/*/blacklist/**";

    /**
     * A single repository, matched with one wildcard segment so that it covers
     * {@code /api/v1/repositories/{name}} without reaching the nested cache and
     * blacklist paths above.
     *
     * <p>Only {@code DELETE} is gated on it. The chain explains why the other
     * verbs on this controller stay as they are.
     */
    public static final String REPOSITORY_ITEM_PATH_PATTERN = "/api/v1/repositories/*";

    /**
     * A single component. Only {@code DELETE} is gated: deletion cascades to
     * the component's assets and removes their blobs from storage.
     *
     * <p>The one wildcard segment also spans {@code /api/v1/components/upload},
     * which is why the rule has to be tied to {@code DELETE}. That path is the
     * documented publish endpoint (admin-guide.md §5, three CI recipes) and
     * belongs to {@code UploadController}, a second controller sharing this
     * prefix; a subtree rule here would have locked out every build job that
     * publishes an artifact.
     */
    public static final String COMPONENT_ITEM_PATH_PATTERN = "/api/v1/components/*";

    /**
     * A single asset. Only {@code DELETE} is gated: it removes the backing blob
     * from storage as well as the row.
     */
    public static final String ASSET_ITEM_PATH_PATTERN = "/api/v1/assets/*";

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
                        // Operational administration: the artifacts, the
                        // storage they sit in, and the machinery that removes
                        // them. A tier below the two blocks above in that none
                        // of it grants a privilege, but the only one of the
                        // three whose blast radius is destruction rather than
                        // disclosure — a triggered cleanup task or a deleted
                        // repository does not come back.
                        //
                        // These four are whole-prefix rules, reads included.
                        // Their only clients are the /admin/* pages of the web
                        // UI, and no doc or script in the repository calls any
                        // of them: neither docs/admin-guide.md nor
                        // docs/migration-from-nexus.md has a section for tasks,
                        // blob stores, cleanup policies or routing rules, and
                        // README.md lists them in a table of endpoint
                        // categories without an example. Nothing that a
                        // customer could have scripted from the documentation
                        // reaches them.
                        .requestMatchers(TASK_ADMIN_PATH_PATTERN)
                        .hasRole(ADMIN_ROLE)
                        .requestMatchers(BLOB_STORE_ADMIN_PATH_PATTERN)
                        .hasRole(ADMIN_ROLE)
                        .requestMatchers(CLEANUP_POLICY_ADMIN_PATH_PATTERN)
                        .hasRole(ADMIN_ROLE)
                        .requestMatchers(ROUTING_RULE_ADMIN_PATH_PATTERN)
                        .hasRole(ADMIN_ROLE)
                        // Reads, gated for what they contain rather than for
                        // what they change: both carry userId, IP address and
                        // artifact path per event, which is a record of what
                        // the customer's developers do all day. The audit page
                        // already lives under Administration in the sidebar and
                        // the activity endpoints have no client at all.
                        .requestMatchers(AUDIT_PATH_PATTERN)
                        .hasRole(ADMIN_ROLE)
                        .requestMatchers(ACTIVITY_PATH_PATTERN)
                        .hasRole(ADMIN_ROLE)
                        // Nested under a repository, and listed before the
                        // repository rules so the narrower path wins. Neither
                        // has a caller in the web UI or in any doc or script —
                        // "blacklist" does not appear outside Java source
                        // anywhere in the repository.
                        .requestMatchers(REPOSITORY_CACHE_PATH_PATTERN)
                        .hasRole(ADMIN_ROLE)
                        .requestMatchers(REPOSITORY_BLACKLIST_PATH_PATTERN)
                        .hasRole(ADMIN_ROLE)
                        // From here on the rules are verb-precise, because the
                        // controllers underneath serve administrators and
                        // ordinary users from one prefix and a subtree rule
                        // would take both.
                        //
                        // Repositories: DELETE only. Creating and updating a
                        // repository is the single most heavily documented
                        // write in the project — admin-guide.md §5 "Creating a
                        // Repository via API", the three curl blocks in
                        // migration-from-nexus.md §2.2, test-projects/setup.sh
                        // provisioning nine repositories in a loop, the upgrade
                        // and docker test scripts, and two k6 scenarios. A
                        // customer who followed the migration guide has that in
                        // a bootstrap job, and an idempotent bootstrap does
                        // POST-then-PUT, so both stay where they were. Deleting
                        // a repository appears in no recipe anywhere; the
                        // Playwright suite that does delete one drives the UI
                        // as admin rather than calling the API.
                        //
                        // The reads stay open for a plainer reason: GET
                        // /api/v1/repositories is called from eight pages of
                        // the web UI, three of them the ordinary user's own
                        // (dashboard, browse, upload), and from seven scripted
                        // call sites.
                        .requestMatchers(HttpMethod.DELETE, REPOSITORY_ITEM_PATH_PATTERN)
                        .hasRole(ADMIN_ROLE)
                        // Components and assets: DELETE only, for the same
                        // reason in reverse. Both deletes destroy artifacts and
                        // their blobs and appear in no recipe. Everything else
                        // these two prefixes carry is what a read-only account
                        // is for — browsing components, and the documented
                        // POST /api/v1/components/upload that a build job runs
                        // after mvn package, npm pack or dotnet pack.
                        //
                        // The delete buttons that reach these two endpoints sit
                        // on the component detail page under /browse, which
                        // every logged-in user can open, and the frontend on
                        // this branch has no role gating at all. A non-admin
                        // will therefore see the buttons and get a 403 toast;
                        // hiding them is a frontend change and is tracked
                        // separately.
                        .requestMatchers(HttpMethod.DELETE, COMPONENT_ITEM_PATH_PATTERN)
                        .hasRole(ADMIN_ROLE)
                        .requestMatchers(HttpMethod.DELETE, ASSET_ITEM_PATH_PATTERN)
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
