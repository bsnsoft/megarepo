package de.bsnsoft.megarepo.app;

import de.bsnsoft.megarepo.app.EndpointAuthorizationInventory.Decision;
import de.bsnsoft.megarepo.app.EndpointAuthorizationInventory.Entry;
import de.bsnsoft.megarepo.database.repository.AnonymousAccessJpaRepository;
import de.bsnsoft.megarepo.database.repository.UserJpaRepository;
import de.bsnsoft.megarepo.security.SecurityConfig;
import de.bsnsoft.megarepo.security.auth.AnonymousAccessFilter;
import de.bsnsoft.megarepo.security.auth.JwtAuthenticationFilter;
import de.bsnsoft.megarepo.security.auth.ratelimit.LoginRateLimitFilter;
import de.bsnsoft.megarepo.security.auth.ratelimit.LoginRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcherEntry;
import org.springframework.stereotype.Controller;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.GenericWebApplicationContext;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * No endpoint without a deliberate authorization decision.
 *
 * <p>The three authorization findings on this codebase were one omission
 * repeated: a controller was written, no matcher in
 * {@link SecurityConfig#filterChain} named it, and the blanket
 * {@code .requestMatchers("/api/v1/**").authenticated()} at the end of the chain
 * quietly handed it to every logged-in account — the seeded read-only
 * {@code nx-viewer} and every LDAP user without a group mapping included. There
 * is no method security in this project, so nothing else was going to catch it.
 * Nobody decided to leave those endpoints open; nobody was ever asked.
 *
 * <p>This test asks. It enumerates every HTTP endpoint the application actually
 * serves and requires each one to appear in
 * {@link EndpointAuthorizationInventory}, with a decision and a written reason,
 * and it then checks that reason against what the real filter chain does. A new
 * controller costs one deliberate line — or the build goes red and says which
 * controller, which path and which verb.
 *
 * <h2>How the endpoints are found</h2>
 *
 * <p>Out of Spring's own {@link RequestMappingHandlerMapping}, not out of a text
 * search. Controller <em>classes</em> are located by annotation scanning and
 * registered as lazy bean definitions in a throwaway context; the handler
 * mapping then derives the mappings from those types exactly as it does at
 * runtime, which picks up inherited handler methods, class-level prefixes,
 * multi-path and multi-verb {@code @RequestMapping} declarations and anything
 * else a grep for {@code @GetMapping} would miss. The controllers are never
 * instantiated — {@code RequestMappingHandlerMapping} works from the bean
 * <em>type</em> — so this needs no database, no mock for any collaborator, and
 * no Testcontainer.
 *
 * <h2>How the decisions are checked</h2>
 *
 * <p>Two ways, because neither alone is enough.
 *
 * <p>Behaviourally: each endpoint is driven through the real {@link SecurityConfig}
 * filter chain three times — anonymous, as {@code nx-viewer}, as {@code nx-admin}
 * — and the decision is read off the statuses. No controller is registered in
 * this context, so a request the chain lets through ends at 404 and the three
 * outcomes separate cleanly: 401 means credentials are required, 403 means the
 * role is, 404 means it was allowed. This is the same technique the existing
 * {@code *AuthorizationTest} classes use, and it proves what the shipped
 * application does rather than what a copy of the rules says.
 *
 * <p>Structurally: 401-then-404 cannot by itself tell "a matcher says any
 * logged-in user may" apart from "the blanket rule caught it", and that
 * difference is the entire subject of this test. So the chain's own matcher list
 * is read out of the {@link AuthorizationFilter} and the endpoint is asked which
 * entry matches it first. The blanket entry is identified without parsing
 * anything: it is whichever entry matches a {@code /api/v1/...} path that no
 * endpoint exists at, since by construction nothing else can match that.
 */
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = {SecurityConfig.class, EndpointAuthorizationInventoryTest.TestConfig.class})
class EndpointAuthorizationInventoryTest {

    /** The package the application component-scans; see {@code MegaRepoApplication}. */
    private static final String BASE_PACKAGE = "de.bsnsoft.megarepo";

    /** Filled into path variables and trailing wildcards to get a concrete request path. */
    private static final String PATH_SAMPLE = "probe";

    /**
     * A {@code /api/v1} path that no controller maps, used to find out which
     * entry of the chain is the blanket fallback.
     */
    private static final String FALLBACK_PROBE_PATH = "/api/v1/" + PATH_SAMPLE + "-no-such-endpoint/x";

    /**
     * Floors that keep this test from passing by finding nothing. If the
     * classpath scan or the handler mapping ever breaks, every assertion below
     * would hold vacuously over an empty list; these two say out loud how much
     * the application is known to have.
     */
    private static final int MINIMUM_CONTROLLERS = 25;

    private static final int MINIMUM_ENDPOINTS = 100;

    private static List<Endpoint> endpoints;

    @Autowired private WebApplicationContext context;

    private MockMvc mockMvc;

    private List<RequestMatcherEntry<?>> chainRules;

    private int fallbackRuleIndex;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        // No reset() of the mock beans below, unlike the sibling authorization
        // tests: nothing here stubs or verifies them. They exist only so that
        // AnonymousAccessFilter and LoginRateLimitFilter can run against
        // /repository/**, /v2/** and the login path without dereferencing null,
        // and their default answers — an empty Optional, "not rate limited" —
        // are exactly what is wanted on every request.
        chainRules = readAuthorizationRules();
        fallbackRuleIndex = indexOfFirstMatchingRule(HttpMethod.GET, FALLBACK_PROBE_PATH);
        if (endpoints == null) {
            endpoints = enumerateEndpoints();
        }
    }

    // ── The tests ───────────────────────────────────────────────────────

    @Test
    @DisplayName("the enumeration actually saw the application")
    void theEnumerationSawTheApplication() {
        long controllers =
                endpoints.stream().map(Endpoint::controller).distinct().count();

        assertThat(controllers)
                .withFailMessage(
                        "Only %d controller(s) were found by scanning %s. This test can only protect endpoints "
                                + "it can see, and at that count it is almost certainly the scan that broke, not "
                                + "the application that shrank. Check that megarepo-app still depends on every "
                                + "module that contributes controllers.",
                        controllers, BASE_PACKAGE)
                .isGreaterThanOrEqualTo(MINIMUM_CONTROLLERS);

        assertThat(endpoints.size())
                .withFailMessage(
                        "Only %d endpoint(s) were enumerated. Same reasoning as above: an empty or truncated "
                                + "enumeration makes every other assertion in this class pass for free.",
                        endpoints.size())
                .isGreaterThanOrEqualTo(MINIMUM_ENDPOINTS);
    }

    @Test
    @DisplayName("the fallback rule really is the last word for /api/v1")
    void theFallbackRuleIsIdentified() throws Exception {
        assertThat(fallbackRuleIndex)
                .withFailMessage("No rule in the chain matched %s, so the blanket /api/v1/** rule could not be "
                        + "identified and AUTHENTICATED_BY_FALLBACK cannot be told apart from AUTHENTICATED. "
                        + "Has the trailing .requestMatchers(\"/api/v1/**\").authenticated() been removed from "
                        + "SecurityConfig?", FALLBACK_PROBE_PATH)
                .isGreaterThanOrEqualTo(0);

        // And it is an authenticated() rule, not a permitAll() one: an unknown
        // /api/v1 path must ask for credentials.
        assertThat(status(HttpMethod.GET, FALLBACK_PROBE_PATH, null)).isEqualTo(401);
        assertThat(status(HttpMethod.GET, FALLBACK_PROBE_PATH, viewer())).isEqualTo(404);
    }

    @Test
    @DisplayName("the inventory itself is well formed")
    void theInventoryIsWellFormed() {
        List<String> problems = new ArrayList<>();

        for (Endpoint endpoint : endpoints) {
            List<Entry> exact = matchingEntries(endpoint, false);
            if (exact.size() > 1) {
                problems.add("%s is claimed by %d exact inventory entries; exactly one may speak for it."
                        .formatted(endpoint.describe(), exact.size()));
            }
            if (exact.isEmpty()) {
                List<Entry> subtrees = matchingEntries(endpoint, true);
                long widest = subtrees.stream()
                        .mapToInt(e -> e.pattern().length())
                        .max()
                        .orElse(-1);
                long ties = subtrees.stream()
                        .filter(e -> e.pattern().length() == widest)
                        .count();
                if (ties > 1) {
                    problems.add("%s is claimed by %d equally specific subtree entries: %s"
                            .formatted(
                                    endpoint.describe(),
                                    ties,
                                    subtrees.stream().map(Entry::pattern).collect(Collectors.joining(", "))));
                }
            }
        }

        if (!problems.isEmpty()) {
            fail("The inventory is ambiguous. Each endpoint must be spoken for by one entry, otherwise the "
                    + "decision that applies to it depends on list order.\n\n  "
                    + String.join("\n  ", problems));
        }
    }

    @Test
    @DisplayName("every endpoint of the application is in the authorization inventory")
    void everyEndpointIsInTheInventory() {
        List<Endpoint> missing = endpoints.stream()
                .filter(e -> entryFor(e) == null)
                .sorted(Comparator.comparing(Endpoint::pattern).thenComparing(e -> e.method().name()))
                .toList();

        if (!missing.isEmpty()) {
            fail(missingEntriesMessage(missing));
        }
    }

    @Test
    @DisplayName("the inventory says what the real filter chain does")
    void theInventoryAgreesWithTheChain() {
        List<String> mismatches = new ArrayList<>();

        for (Endpoint endpoint : endpoints) {
            Entry entry = entryFor(endpoint);
            if (entry == null) {
                continue; // reported by everyEndpointIsInTheInventory
            }
            Observation observed = observe(endpoint);
            if (observed.decision() != entry.decision()) {
                mismatches.add(mismatchDetail(endpoint, entry, observed));
            }
        }

        if (!mismatches.isEmpty()) {
            fail(mismatchMessage(mismatches));
        }
    }

    @Test
    @DisplayName("no inventory entry has outlived its endpoint")
    void noInventoryEntryIsStale() {
        List<Entry> stale = EndpointAuthorizationInventory.ENTRIES.stream()
                .filter(entry -> endpoints.stream().noneMatch(e -> entryFor(e) == entry))
                .toList();

        if (!stale.isEmpty()) {
            String list = stale.stream()
                    .map(e -> "  %-12s %-46s %s".formatted(e.describeMethods(), e.pattern(), e.decision()))
                    .collect(Collectors.joining("\n"));
            fail(
                    """
                    %d inventory entr(y/ies) speak for no endpoint that exists any more.

                    %s

                    An entry that matches nothing is a decision about code that has been renamed or
                    deleted. Left in place it makes the inventory look more complete than it is, and it
                    hides the moment when the endpoint comes back under a different path with no
                    decision attached. Delete it, or correct the pattern if the endpoint only moved.

                    Note that an entry can also fall out of use by being shadowed: an exact entry stops
                    matching if the mapping it names gains or loses a path variable, and a carve-out
                    stops matching if the subtree entry above it grew to cover the same verb.
                    """
                            .formatted(stale.size(), list));
        }
    }

    /**
     * The carve-outs that live <em>inside</em> a wildcard mapping, pinned by
     * hand.
     *
     * <p>{@code DockerV2Controller} maps the whole registry as {@code /v2} and
     * {@code /v2/**} per verb, and {@code RepositoryRouter} maps
     * {@code /repository/{repoName}/**}. One mapping, several chain rules: the
     * inventory decides per mapping and the sampled path cannot show all of
     * them. These are the concrete paths that matter, asserted as themselves.
     */
    @Test
    @DisplayName("the public paths inside the Docker and repository mappings stay public, the rest does not")
    void dockerAndRepositoryCarveOuts() throws Exception {
        // Docker: the version check and the token endpoint are what a client
        // reaches before it has any credentials.
        assertThat(status(HttpMethod.GET, "/v2/", null)).as("GET /v2/ (registry version check)").isEqualTo(404);
        assertThat(status(HttpMethod.GET, "/v2/token", null)).as("GET /v2/token").isEqualTo(404);
        assertThat(status(HttpMethod.POST, "/v2/token", null)).as("POST /v2/token").isEqualTo(404);

        // Everything else under /v2 needs credentials, pulls included.
        assertThat(status(HttpMethod.GET, "/v2/library/app/manifests/latest", null))
                .as("GET a manifest, anonymously")
                .isEqualTo(401);
        assertThat(status(HttpMethod.PUT, "/v2/library/app/blobs/uploads/1", null))
                .as("PUT a blob, anonymously")
                .isEqualTo(401);
        assertThat(status(HttpMethod.GET, "/v2/library/app/manifests/latest", viewer()))
                .as("GET a manifest as a plain user")
                .isEqualTo(404);

        // Repository: reads public, writes credentialled, at every depth.
        assertThat(status(HttpMethod.GET, "/repository/maven-central/org/example/lib/1.0/lib-1.0.jar", null))
                .as("GET an artifact, anonymously")
                .isEqualTo(404);
        assertThat(status(HttpMethod.HEAD, "/repository/maven-central/org/example/lib/1.0/lib-1.0.jar", null))
                .as("HEAD an artifact, anonymously")
                .isEqualTo(404);
        assertThat(status(HttpMethod.PUT, "/repository/maven-internal/org/example/lib/1.0/lib-1.0.jar", null))
                .as("PUT an artifact, anonymously")
                .isEqualTo(401);
        assertThat(status(HttpMethod.PUT, "/repository/maven-internal/org/example/lib/1.0/lib-1.0.jar", viewer()))
                .as("PUT an artifact as a plain user — the documented CI identity is not an admin")
                .isEqualTo(404);

        // And the status pair, which the chain splits at an exact path.
        assertThat(status(HttpMethod.GET, "/api/v1/status", null)).as("GET /api/v1/status").isEqualTo(404);
        assertThat(status(HttpMethod.GET, "/api/v1/status/check", null))
                .as("GET /api/v1/status/check")
                .isEqualTo(401);
    }

    // ── Failure messages ────────────────────────────────────────────────

    private static String missingEntriesMessage(List<Endpoint> missing) {
        String list = missing.stream()
                .map(e -> "  %-7s %-52s  (%s#%s)".formatted(e.method().name(), e.pattern(), e.controller(), e.handler()))
                .collect(Collectors.joining("\n"));

        return """
               %d endpoint(s) reach this application without an authorization decision.

               %s

               Why this is failing
               -------------------
               MegaRepo has no method security. There is no @EnableMethodSecurity in the build, so a
               @PreAuthorize on a controller method does nothing at all — it neither grants nor denies.
               Authorization exists in exactly one place, the matcher list of

                   de.bsnsoft.megarepo.security.SecurityConfig#filterChain

               and that list ends with

                   .requestMatchers("/api/v1/**").authenticated()

               A new endpoint under /api/v1 that no matcher names is therefore not unreachable and not
               obviously broken. It is open to every account that can log in, including the seeded
               read-only nx-viewer and every LDAP user whose groups map to no role. Three security
               findings on this codebase were exactly that, and in none of them had anyone decided to
               leave the endpoint open.

               What to do, for each endpoint above
               -----------------------------------
               1. Decide who may call it. Read what it returns and what it changes, and decide whether
                  an account that can only browse artifacts may have it.

               2. If it needs a role, add a matcher for it in SecurityConfig#filterChain, ahead of the
                  blanket /api/v1/** rule. The first matching rule wins, so a rule written after a
                  broader one never takes effect — put the narrow path first. Follow the constants at
                  the top of that class: each pattern is a named constant with the reasoning next to it.

               3. Add one line to EndpointAuthorizationInventory#ENTRIES, in the section for the
                  decision you made, with a sentence saying why. The reason is a mandatory field. It is
                  the part of this exercise that has value: it is what lets the next person disagree
                  with you instead of assuming you knew something they do not.

                  ADMIN_ONLY                 the nx-admin role is required.
                  AUTHENTICATED              any logged-in account, and a matcher in the chain says so.
                  AUTHENTICATED_BY_FALLBACK  any logged-in account, and only the blanket /api/v1/**
                                             rule says so. Legitimate — much of the browse, search and
                                             publish surface is here on purpose — but write it only
                                             after you have looked, and say in the rationale what you
                                             saw. This is the category every one of the three findings
                                             was sitting in.
                  PUBLIC                     reachable with no credentials at all.

               Files
               -----
               inventory : megarepo-app/src/test/java/de/bsnsoft/megarepo/app/EndpointAuthorizationInventory.java
               chain     : megarepo-security/src/main/java/de/bsnsoft/megarepo/security/SecurityConfig.java
               this test : megarepo-app/src/test/java/de/bsnsoft/megarepo/app/EndpointAuthorizationInventoryTest.java
               """
                .formatted(missing.size(), list);
    }

    private String mismatchDetail(Endpoint endpoint, Entry entry, Observation observed) {
        return """
                 %s   (%s#%s)
                     inventory says  : %s
                     the chain does  : %s
                     evidence        : anonymous -> %d, nx-viewer -> %d, nx-admin -> %d   (probed at %s)
                     matched first by: %s
                     rationale on record:
                       "%s"\
               """
                .formatted(
                        endpoint.describe(),
                        endpoint.controller(),
                        endpoint.handler(),
                        entry.decision(),
                        observed.decision(),
                        observed.anonymous(),
                        observed.viewer(),
                        observed.admin(),
                        endpoint.samplePath(),
                        observed.matchedRule(),
                        entry.rationale());
    }

    private static String mismatchMessage(List<String> mismatches) {
        return """
               %d endpoint(s) are classified one way in the inventory and another way by the real
               filter chain.

               %s

               How to read this
               ----------------
               The statuses come from driving the endpoint through the actual SecurityConfig chain with
               no controller registered, so anything the chain lets through ends at 404:

                   401 anywhere          credentials are required
                   403 for nx-viewer     a role is required
                   404                   allowed through

               "matched first by" is the entry of the chain's own matcher list that claims the path.
               When it is the blanket /api/v1/** rule, no matcher names this endpoint and the honest
               label is AUTHENTICATED_BY_FALLBACK, not AUTHENTICATED — the access is identical, the
               statement is not.

               One of the two sides is wrong. If the chain is wrong, fix SecurityConfig. If the
               inventory is wrong, fix the entry — but do not simply relabel it to whatever the chain
               happens to do today without re-reading the matcher, because a rule that silently stopped
               applying (a path renamed out from under a prefix, a narrow rule written after a broad
               one) looks exactly like this from here, and relabelling is how it would get blessed.
               """
                .formatted(mismatches.size(), String.join("\n\n", mismatches));
    }

    // ── Inventory lookup ────────────────────────────────────────────────

    /**
     * The one entry that speaks for an endpoint: an exact entry if there is one,
     * otherwise the most specific subtree entry, otherwise nothing.
     */
    private static Entry entryFor(Endpoint endpoint) {
        List<Entry> exact = matchingEntries(endpoint, false);
        if (!exact.isEmpty()) {
            return exact.get(0);
        }
        return matchingEntries(endpoint, true).stream()
                .max(Comparator.comparingInt(e -> e.pattern().length()))
                .orElse(null);
    }

    private static List<Entry> matchingEntries(Endpoint endpoint, boolean subtree) {
        return EndpointAuthorizationInventory.ENTRIES.stream()
                .filter(e -> e.subtree() == subtree)
                .filter(e -> e.coversMethod(endpoint.method()))
                .filter(e -> e.coversPath(endpoint.pattern(), endpoint.samplePath()))
                .toList();
    }

    // ── Enumeration ─────────────────────────────────────────────────────

    /**
     * Every mapping the application serves, taken from a real
     * {@link RequestMappingHandlerMapping}.
     *
     * <p>The controllers go in as lazy bean definitions and are never
     * instantiated: {@code RequestMappingHandlerMapping} reads the bean
     * <em>type</em> from the bean factory and builds {@link HandlerMethod}s that
     * resolve their target on first use, which never happens here. That is what
     * makes it possible to see all 30-odd controllers from every module at once
     * without standing up their dependencies.
     */
    private static List<Endpoint> enumerateEndpoints() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        // @RestController is meta-annotated with @Controller, and AnnotationTypeFilter
        // follows meta-annotations. The second filter catches a handler that carries
        // @RequestMapping on the type without a stereotype, which RequestMappingHandlerMapping
        // also treats as a handler.
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(RequestMapping.class));

        Set<Class<?>> controllerTypes = new LinkedHashSet<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(BASE_PACKAGE)) {
            try {
                controllerTypes.add(Class.forName(definition.getBeanClassName()));
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("scanned " + definition.getBeanClassName() + " but cannot load it", e);
            }
        }

        try (GenericWebApplicationContext scratch = new GenericWebApplicationContext(new MockServletContext())) {
            for (Class<?> type : controllerTypes) {
                RootBeanDefinition definition = new RootBeanDefinition(type);
                definition.setLazyInit(true); // never construct one: their collaborators are not here
                scratch.registerBeanDefinition(type.getName(), definition);
            }
            scratch.refresh();

            RequestMappingHandlerMapping handlerMapping = new RequestMappingHandlerMapping();
            handlerMapping.setApplicationContext(scratch);
            handlerMapping.afterPropertiesSet();

            List<Endpoint> found = new ArrayList<>();
            for (Map.Entry<RequestMappingInfo, HandlerMethod> mapping :
                    handlerMapping.getHandlerMethods().entrySet()) {
                RequestMappingInfo info = mapping.getKey();
                HandlerMethod handler = mapping.getValue();
                for (String pattern : patternsOf(info)) {
                    for (HttpMethod method : methodsOf(info)) {
                        found.add(new Endpoint(
                                pattern,
                                method,
                                handler.getBeanType().getSimpleName(),
                                handler.getMethod().getName()));
                    }
                }
            }
            found.sort(Comparator.comparing(Endpoint::pattern).thenComparing(e -> e.method().name()));
            return List.copyOf(found);
        }
    }

    private static Set<String> patternsOf(RequestMappingInfo info) {
        Set<String> patterns = new LinkedHashSet<>();
        if (info.getPathPatternsCondition() != null) {
            patterns.addAll(info.getPathPatternsCondition().getPatternValues());
        }
        if (patterns.isEmpty() && info.getPatternsCondition() != null) {
            patterns.addAll(info.getPatternsCondition().getPatterns());
        }
        if (patterns.isEmpty()) {
            // A mapping with no path at all would answer for every request that
            // reaches the dispatcher; there is no such thing here, and if one
            // appears it must not slip past unnoticed.
            throw new IllegalStateException("handler mapping without a path pattern: " + info);
        }
        return patterns;
    }

    /**
     * The verbs a mapping answers. A mapping that names none answers all of
     * them, and is expanded to the full set rather than collapsed to one, so
     * that a rule which gates only DELETE cannot hide behind a GET probe.
     */
    private static Set<HttpMethod> methodsOf(RequestMappingInfo info) {
        Set<HttpMethod> methods = info.getMethodsCondition().getMethods().stream()
                .map(m -> HttpMethod.valueOf(m.name()))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return methods.isEmpty()
                ? new LinkedHashSet<>(List.of(
                        HttpMethod.GET,
                        HttpMethod.HEAD,
                        HttpMethod.POST,
                        HttpMethod.PUT,
                        HttpMethod.PATCH,
                        HttpMethod.DELETE))
                : methods;
    }

    // ── Driving the real chain ──────────────────────────────────────────

    private Observation observe(Endpoint endpoint) {
        int anonymous = status(endpoint.method(), endpoint.samplePath(), null);
        int viewer = status(endpoint.method(), endpoint.samplePath(), viewer());
        int admin = status(endpoint.method(), endpoint.samplePath(), admin());

        int ruleIndex = indexOfFirstMatchingRule(endpoint.method(), endpoint.samplePath());
        String matchedRule = describeRule(ruleIndex);

        Decision decision;
        if (anonymous != 401 && anonymous != 403) {
            decision = Decision.PUBLIC;
        } else if (viewer == 403) {
            decision = admin == 403 ? null : Decision.ADMIN_ONLY;
        } else {
            decision = ruleIndex == fallbackRuleIndex ? Decision.AUTHENTICATED_BY_FALLBACK : Decision.AUTHENTICATED;
        }
        if (decision == null) {
            throw new IllegalStateException("the chain denies " + endpoint.describe()
                    + " to nx-admin as well as to nx-viewer, which no inventory category describes. "
                    + "Either a rule requires a role nobody holds, or this test needs a new category.");
        }
        return new Observation(decision, anonymous, viewer, admin, matchedRule);
    }

    private int status(HttpMethod method, String path, RequestPostProcessor as) {
        try {
            var request = MockMvcRequestBuilders.request(method, path);
            if (as != null) {
                request = request.with(as);
            }
            return mockMvc.perform(request).andReturn().getResponse().getStatus();
        } catch (Exception e) {
            throw new IllegalStateException("could not drive " + method + " " + path + " through the chain", e);
        }
    }

    /**
     * The chain's own matcher list, read out of the {@link AuthorizationFilter}
     * that {@code authorizeHttpRequests} installs.
     *
     * <p>There is no public accessor for it. The alternative — restating the
     * rules in this test so that the fallback can be recognised — would make the
     * test agree with a copy of the configuration instead of with the
     * configuration, which is the failure mode the whole exercise exists to
     * avoid. So: reflection, deliberately, on one private field, with an error
     * message that says where to look when a Spring Security upgrade moves it.
     */
    private List<RequestMatcherEntry<?>> readAuthorizationRules() {
        FilterChainProxy proxy = context.getBean("springSecurityFilterChain", FilterChainProxy.class);
        AuthorizationFilter filter = proxy.getFilterChains().stream()
                .map(SecurityFilterChain::getFilters)
                .flatMap(List::stream)
                .filter(AuthorizationFilter.class::isInstance)
                .map(AuthorizationFilter.class::cast)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "no AuthorizationFilter in the security filter chain — has authorizeHttpRequests been "
                                + "removed from SecurityConfig?"));

        Object manager = filter.getAuthorizationManager();
        for (Class<?> type = manager.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (List.class.isAssignableFrom(field.getType())) {
                    try {
                        field.setAccessible(true);
                        @SuppressWarnings("unchecked")
                        List<RequestMatcherEntry<?>> rules = (List<RequestMatcherEntry<?>>) field.get(manager);
                        return rules;
                    } catch (ReflectiveOperationException | ClassCastException e) {
                        throw new IllegalStateException("could not read the matcher list out of " + type, e);
                    }
                }
            }
        }
        throw new IllegalStateException(
                "no matcher list found on " + manager.getClass().getName()
                        + ". Spring Security keeps the rules of authorizeHttpRequests in a private List field of "
                        + "RequestMatcherDelegatingAuthorizationManager; an upgrade appears to have moved it. "
                        + "This test needs it in order to tell an endpoint that a matcher names apart from one "
                        + "that only the blanket /api/v1/** rule caught.");
    }

    private int indexOfFirstMatchingRule(HttpMethod method, String path) {
        HttpServletRequest request =
                MockMvcRequestBuilders.request(method, path).buildRequest(context.getServletContext());
        for (int i = 0; i < chainRules.size(); i++) {
            RequestMatcher matcher = chainRules.get(i).getRequestMatcher();
            if (matcher.matches(request)) {
                return i;
            }
        }
        return -1;
    }

    private String describeRule(int index) {
        if (index < 0) {
            return "no rule at all (the chain has no anyRequest() catch-all?)";
        }
        if (index == fallbackRuleIndex) {
            return "the blanket `/api/v1/**` rule at the end of the chain — no matcher names this path";
        }
        return "rule #" + index + " of the chain: " + chainRules.get(index).getRequestMatcher();
    }

    private static RequestPostProcessor viewer() {
        // The seeded read-only account, and what an LDAP user gets when no group
        // maps to a role. The account every one of the three findings was
        // exploitable from.
        return user("probe-viewer").roles("nx-viewer");
    }

    private static RequestPostProcessor admin() {
        return user("probe-admin").roles(SecurityConfig.ADMIN_ROLE);
    }

    // ── Types ───────────────────────────────────────────────────────────

    /** One mapped verb on one path pattern, and where it comes from. */
    private record Endpoint(String pattern, HttpMethod method, String controller, String handler) {

        /**
         * A concrete path to send, with path variables and the trailing wildcard
         * filled in. Deliberately a word no route treats specially, so that the
         * probe cannot land on a narrower rule by accident — a {@code {userId}}
         * filled in as {@code me}, for instance, would have been answered by the
         * self-service carve-out instead of the user-administration rule.
         */
        String samplePath() {
            String path = pattern.replaceAll("\\{[^{}/]*}", PATH_SAMPLE);
            if (path.endsWith("/**")) {
                path = path.substring(0, path.length() - 3) + "/" + PATH_SAMPLE;
            }
            path = path.replace("*", PATH_SAMPLE);
            return path;
        }

        String describe() {
            return "%-7s %s".formatted(method.name(), pattern);
        }
    }

    /** What the chain did, and which rule did it. */
    private record Observation(Decision decision, int anonymous, int viewer, int admin, String matchedRule) {}

    // ── Context ─────────────────────────────────────────────────────────

    /**
     * The real {@link SecurityConfig} and nothing else.
     *
     * <p>No controller is registered on purpose: the point is to observe the
     * chain, and with no handler behind it every permitted request ends at a
     * clean 404, which makes "allowed" distinguishable from "401" and "403"
     * without any controller having to be constructible.
     *
     * <p>The three collaborating filters get mocked repositories rather than the
     * {@code null}s the sibling authorization tests pass, because this test also
     * drives {@code /repository/**}, {@code /v2/**} and the login path, and
     * those are the paths on which {@code AnonymousAccessFilter} and
     * {@code LoginRateLimitFilter} actually dereference their dependencies. The
     * default answers are the ones wanted throughout: no anonymous access
     * configured, nobody rate limited.
     */
    @Configuration
    @EnableWebMvc
    static class TestConfig {

        @Bean
        JwtAuthenticationFilter jwtAuthenticationFilter() {
            // Never reached: no request here carries a bearer token or a NuGet
            // API key, and the filter returns before touching the provider.
            return new JwtAuthenticationFilter(null);
        }

        @Bean
        AnonymousAccessFilter anonymousAccessFilter() {
            return new AnonymousAccessFilter(
                    mock(AnonymousAccessJpaRepository.class), mock(UserJpaRepository.class));
        }

        @Bean
        LoginRateLimitFilter loginRateLimitFilter() {
            return new LoginRateLimitFilter(mock(LoginRateLimiter.class));
        }

        /** Named to match the parameter {@code SecurityConfig#filterChain} declares. */
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
