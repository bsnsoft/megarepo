package de.bsnsoft.megarepo.app;

import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The authorization decision that has been taken for every HTTP endpoint this
 * application serves, together with the reason it was taken.
 *
 * <p>This file exists because MegaRepo has no method security. There is no
 * {@code @EnableMethodSecurity} anywhere in the build, so a {@code @PreAuthorize}
 * on a controller method authorizes nobody and denies nobody — it is inert.
 * Authorization lives in exactly one place, the matcher list of
 * {@link de.bsnsoft.megarepo.security.SecurityConfig#filterChain}, and that list
 * ends with:
 *
 * <pre>{@code   .requestMatchers("/api/v1/**").authenticated()}</pre>
 *
 * <p>A new controller under {@code /api/v1} that names no matcher of its own is
 * therefore not unprotected in a way anyone notices — it is quietly open to
 * every logged-in account. That includes the seeded read-only {@code nx-viewer}
 * and, because no group mapping is required, every LDAP user whose groups map to
 * nothing. Three separate security findings on this codebase were that same
 * omission: the NVD firewall API, {@code /api/v1/security/users} (which let any
 * account grant itself {@code nx-admin}), and the writing operational endpoints.
 * In none of the three did anyone decide to leave the endpoint open. Nobody was
 * ever asked.
 *
 * <p>Method security was considered as the structural fix and deliberately not
 * adopted: it is opt-in per method, so switching it on would have changed the
 * attack surface by nothing on the day it landed, and it would have created a
 * second place where authorization is expressed. The chain stays the single
 * authority because it can be read end to end in one sitting. The structural
 * protection is this inventory instead: {@link EndpointAuthorizationInventoryTest}
 * enumerates every endpoint out of Spring's own handler mapping and fails if one
 * of them is not listed here, so a new controller costs exactly one deliberate
 * line — or it falls over.
 *
 * <h2>How to add an entry</h2>
 *
 * <ol>
 *   <li>Decide who may call the endpoint, and put the matcher for that decision
 *       into {@code SecurityConfig#filterChain} <em>ahead</em> of the blanket
 *       {@code /api/v1/**} rule. The first matching rule wins, so a later,
 *       broader entry never tightens an earlier one.
 *   <li>Add one line below, in the section for that decision, with a rationale.
 *       The rationale is a mandatory field and is the actual point of this file:
 *       it is what lets the next reader disagree with you.
 * </ol>
 *
 * <p>Use {@link #adminSubtree} only for whole prefixes that the chain itself
 * gates as a subtree. It is the one form that lets a future endpoint join
 * without a line of its own, which is safe precisely because the decision it
 * inherits is the restrictive one. Every other decision has to name the
 * endpoint's exact mapping pattern, so that a new sibling of a public or
 * merely-authenticated endpoint cannot inherit that openness in silence.
 */
final class EndpointAuthorizationInventory {

    /**
     * What the filter chain does with an endpoint. Ordered from least to most
     * restrictive, except for {@link #AUTHENTICATED_BY_FALLBACK}, which is not a
     * point on that scale at all — see its own note.
     */
    enum Decision {

        /** Reachable without credentials. */
        PUBLIC,

        /**
         * Any logged-in account, and a matcher in the chain says so in as many
         * words. The endpoint has been looked at.
         */
        AUTHENTICATED,

        /**
         * Any logged-in account, but only because the blanket
         * {@code .requestMatchers("/api/v1/**").authenticated()} at the end of
         * the chain caught it. No matcher names this path.
         *
         * <p>Kept as a category of its own rather than folded into
         * {@link #AUTHENTICATED} because the two are the same access and a
         * completely different statement. {@code AUTHENTICATED} means somebody
         * decided; this means the default decided. Every finding this file
         * exists to prevent was sitting in this category at the time it was
         * found.
         *
         * <p>Listing an endpoint here is therefore not a way of waving it
         * through. It is a claim that you went and looked at what the endpoint
         * returns or changes, and concluded that every account which can log in
         * at all — the read-only {@code nx-viewer} included — may really have
         * it. Say so in the rationale.
         */
        AUTHENTICATED_BY_FALLBACK,

        /** Requires the {@code nx-admin} role. */
        ADMIN_ONLY
    }

    /** Any verb: the entry covers every method mapped on its pattern. */
    private static final Set<HttpMethod> ANY_METHOD = Set.of();

    /**
     * One decision, about one path pattern, with the reason for it.
     *
     * @param pattern for an exact entry, the mapping pattern exactly as Spring
     *     reports it (path variables included, e.g. {@code /api/v1/tasks/{id}});
     *     for a subtree entry, the prefix pattern the chain gates
     * @param methods the verbs this entry decides for, or empty for all of them
     * @param decision what the chain is expected to do
     * @param rationale why that is the right answer — mandatory
     * @param subtree whether this entry covers everything below {@code pattern}
     *     rather than one mapping
     */
    record Entry(
            String pattern, Set<HttpMethod> methods, Decision decision, String rationale, boolean subtree) {

        Entry {
            if (pattern == null || pattern.isBlank()) {
                throw new IllegalArgumentException("inventory entry without a path pattern");
            }
            if (rationale == null || rationale.isBlank()) {
                throw new IllegalArgumentException("inventory entry for " + pattern + " has no rationale. "
                        + "The rationale is mandatory: this file is read by whoever has to decide whether the "
                        + "decision is still right, and an entry without one tells them nothing.");
            }
            if (rationale.strip().length() < 25) {
                throw new IllegalArgumentException("inventory entry for " + pattern
                        + " has a rationale of " + rationale.strip().length()
                        + " characters. Say why the decision is right, in a sentence.");
            }
            if (subtree && decision != Decision.ADMIN_ONLY) {
                throw new IllegalArgumentException("inventory entry for " + pattern + " gates a whole subtree as "
                        + decision + ". Only ADMIN_ONLY may be granted to a subtree: a subtree entry lets a "
                        + "future endpoint inherit its decision without a line of its own, and that is only "
                        + "acceptable when the inherited decision is the restrictive one. Name the exact "
                        + "mapping pattern instead.");
            }
            if (subtree && !pattern.endsWith("/**")) {
                throw new IllegalArgumentException("subtree entry " + pattern + " must end in /**");
            }
            methods = Set.copyOf(methods);
        }

        boolean coversMethod(HttpMethod method) {
            return methods.isEmpty() || methods.contains(method);
        }

        /**
         * Whether this entry speaks for the given endpoint. Exact entries match
         * the mapping pattern verbatim; subtree entries match a concrete sample
         * path taken from the mapping, so that a chain prefix written with
         * {@code *} lines up with a mapping written with {@code {name}}.
         */
        boolean coversPath(String mappingPattern, String samplePath) {
            if (!subtree) {
                return pattern.equals(mappingPattern);
            }
            return parsed(pattern).matches(PathContainer.parsePath(samplePath));
        }

        String describeMethods() {
            return methods.isEmpty()
                    ? "any verb"
                    : methods.stream().map(HttpMethod::name).sorted().reduce((a, b) -> a + "/" + b).orElse("");
        }
    }

    private static final Map<String, PathPattern> PARSED = new ConcurrentHashMap<>();

    private static PathPattern parsed(String pattern) {
        return PARSED.computeIfAbsent(pattern, PathPatternParser.defaultInstance::parse);
    }

    // ── Entry constructors ──────────────────────────────────────────────
    //
    // Deliberately verbose at the call site. The decision is the first thing
    // you read on every line below, because the decision is what a reviewer is
    // here for.

    private static Entry exact(String pattern, Set<HttpMethod> methods, Decision decision, String rationale) {
        return new Entry(pattern, methods, decision, rationale, false);
    }

    /** A whole prefix, gated as {@code nx-admin} by a subtree matcher in the chain. */
    private static Entry adminSubtree(String pattern, String rationale) {
        return new Entry(pattern, ANY_METHOD, Decision.ADMIN_ONLY, rationale, true);
    }

    private static Entry publicEndpoint(HttpMethod method, String pattern, String rationale) {
        return exact(pattern, Set.of(method), Decision.PUBLIC, rationale);
    }

    private static Entry authenticated(HttpMethod method, String pattern, String rationale) {
        return exact(pattern, Set.of(method), Decision.AUTHENTICATED, rationale);
    }

    private static Entry authenticated(Set<HttpMethod> methods, String pattern, String rationale) {
        return exact(pattern, methods, Decision.AUTHENTICATED, rationale);
    }

    private static Entry adminOnly(HttpMethod method, String pattern, String rationale) {
        return exact(pattern, Set.of(method), Decision.ADMIN_ONLY, rationale);
    }

    private static Entry adminOnly(String pattern, String rationale) {
        return exact(pattern, ANY_METHOD, Decision.ADMIN_ONLY, rationale);
    }

    /**
     * Any logged-in account, and only the blanket {@code /api/v1/**} rule says
     * so. Spelled out at every call site rather than hidden behind a short name,
     * because the whole value of the category is that it is uncomfortable to
     * write.
     */
    private static Entry authenticatedByFallback(HttpMethod method, String pattern, String rationale) {
        return exact(pattern, Set.of(method), Decision.AUTHENTICATED_BY_FALLBACK, rationale);
    }

    /**
     * Every endpoint of the application, and what may reach it.
     *
     * <p>Filled from the state of {@code main} at the time the three
     * authorization findings were closed. Where an entry looked wrong while it
     * was being written it was still recorded as-is and reported separately —
     * the classifications in the chain were argued out with evidence, and a
     * silent fourth revision hidden in a test file would not have been.
     */
    static final List<Entry> ENTRIES = List.of(

            // ── PUBLIC ──────────────────────────────────────────────────
            //
            // Reachable with no credentials at all, from the internet if the
            // instance is exposed. Six of them, and each is public because a
            // client that has no credentials yet has to be able to call it.

            publicEndpoint(
                    HttpMethod.POST,
                    "/api/v1/security/auth/login",
                    "The endpoint that issues credentials cannot require them. Brute force is answered "
                            + "separately by LoginRateLimitFilter, which is the reason this path is singled out "
                            + "in that filter rather than gated in the chain."),
            publicEndpoint(
                    HttpMethod.POST,
                    "/api/v1/security/auth/refresh",
                    "Takes a refresh token and returns an access token. The token is the credential, so the "
                            + "call authenticates itself; requiring a live access token as well would defeat "
                            + "the point of refresh, which is to be called once the access token has expired."),
            publicEndpoint(
                    HttpMethod.POST,
                    "/api/v1/security/auth/regenerate-token",
                    "Same shape as refresh: the presented token is the credential, and the handler rejects "
                            + "the call if it does not validate."),
            publicEndpoint(
                    HttpMethod.GET,
                    "/api/v1/status",
                    "The liveness probe. Named by an exact matcher rather than a prefix, so it hands out "
                            + "nothing but the fact that the instance answers."),
            publicEndpoint(
                    HttpMethod.GET,
                    "/repository/{repoName}/**",
                    "Artifact download. Public reads are the product: a Maven, npm or pip client resolves "
                            + "from here, and whether an unauthenticated one may is decided per instance by "
                            + "the anonymous-access setting that AnonymousAccessFilter reads, not by the "
                            + "chain. Gating it here would take that setting away from the administrator."),
            publicEndpoint(
                    HttpMethod.HEAD,
                    "/repository/{repoName}/**",
                    "The existence check that every one of those clients makes before a download. Needs "
                            + "its own rule because a matcher bound to GET does not answer for HEAD."),

            // ── AUTHENTICATED ───────────────────────────────────────────
            //
            // Any logged-in account, and a matcher in the chain says so. Six of
            // these are carve-outs written ahead of a stricter rule; the rest
            // are the write side of the format-native and Docker surfaces.

            authenticated(
                    HttpMethod.POST,
                    "/api/v1/firewall/exemptions",
                    "Filing an exemption request, and the one verb on this prefix that is not nx-admin. A "
                            + "request changes nothing — it is created REQUESTED and lets no download through "
                            + "until an approver acts — and the point of the feature is that a developer who "
                            + "hits a firewall 403 can ask from the block page instead of opening a ticket. "
                            + "Whether a non-administrator may file one at all is a runtime property enforced "
                            + "in the controller, which is why it is not a chain decision."),
            authenticated(
                    HttpMethod.GET,
                    "/api/v1/security/users/me",
                    "The caller's own profile, carved out of the nx-admin rule over /api/v1/security/users. "
                            + "Reading your own record is not user administration."),
            authenticated(
                    HttpMethod.PUT,
                    "/api/v1/security/users/me",
                    "Editing your own profile. Safe to carve out because it binds ApiUpdateProfile, which "
                            + "carries names and an e-mail address and no role list — unlike PUT on the "
                            + "per-user path, which is how any account could once grant itself nx-admin."),
            authenticated(
                    HttpMethod.POST,
                    "/api/v1/security/users/me/verify-password",
                    "Confirms the caller's own current password, the step that guards the password change "
                            + "below. Requires knowing the password it verifies."),
            authenticated(
                    HttpMethod.PUT,
                    "/api/v1/security/users/me/change-password",
                    "Changing your own password. Distinct from the administrative reset on the per-user "
                            + "path in that the handler verifies the current password first."),
            authenticated(
                    HttpMethod.GET,
                    "/api/v1/system/license",
                    "The license banner, fetched by the sidebar and the dashboard on every page load for "
                            + "every logged-in user. The deliberate exception inside the nx-admin rule over "
                            + "/api/v1/system: the payload is the edition, the licensee and a seat count, all "
                            + "of which the same user already sees rendered, so gating the read would blank "
                            + "the banner for non-administrators and buy nothing. Installing and removing a "
                            + "license stay administrative."),
            authenticated(
                    Set.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH),
                    "/repository/{repoName}",
                    "Format-native publish against a repository root. Writes need credentials — the "
                            + "/repository/** rule that follows the two public read rules — but not the admin "
                            + "role: the documented CI identity in admin-guide.md is a plain user, and "
                            + "publishing is what it exists to do."),
            authenticated(
                    Set.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE),
                    "/repository/{repoName}/**",
                    "The same publish path one or more segments deep, which is where a Maven or npm client "
                            + "actually PUTs. Same rule, same reasoning."),
            authenticated(
                    Set.of(
                            HttpMethod.GET,
                            HttpMethod.HEAD,
                            HttpMethod.PUT,
                            HttpMethod.POST,
                            HttpMethod.PATCH,
                            HttpMethod.DELETE),
                    "/v2",
                    "The Docker registry root. DockerV2Controller maps the whole /v2 surface as one "
                            + "catch-all per verb and dispatches inside, so the chain decides for the surface "
                            + "rather than per Docker operation: everything under /v2 needs credentials "
                            + "except the version check and the token endpoint. Note that the chain's public "
                            + "carve-out is written as /v2/ with a trailing separator while this mapping is "
                            + "/v2 without one, so this bare path is authenticated — see "
                            + "EndpointAuthorizationInventoryTest#dockerAndRepositoryCarveOuts, which pins "
                            + "the carve-outs that live inside this mapping."),
            authenticated(
                    Set.of(
                            HttpMethod.GET,
                            HttpMethod.HEAD,
                            HttpMethod.PUT,
                            HttpMethod.POST,
                            HttpMethod.PATCH,
                            HttpMethod.DELETE),
                    "/v2/**",
                    "The rest of the Docker registry API: manifests, blobs, uploads, tag lists. Pulling a "
                            + "private image is a credentialled operation for every Docker client, and the "
                            + "two paths a client may reach before it has a token — /v2/ and /v2/token — are "
                            + "carved out ahead of this rule and asserted separately."),

            // ── ADMIN_ONLY ──────────────────────────────────────────────
            //
            // The nx-admin role. Grouped the way the chain groups them:
            // identity and access first, then the instance's own trust and
            // configuration, then the operational surfaces that destroy things.

            adminSubtree(
                    "/api/v1/security/users/**",
                    "User administration: create, list, modify and delete accounts, reset any account's "
                            + "password, and assign roles. The most severe of the three findings — "
                            + "ApiCreateUser carries a free-form role list, so while this fell through to the "
                            + "blanket rule any logged-in account could POST itself a second account holding "
                            + "nx-admin. Every other rule in the chain was only as strong as this one "
                            + "omission. The four self-service paths are carved out above."),
            adminSubtree(
                    "/api/v1/security/roles/**",
                    "Role definitions: which privileges a role carries and which roles it nests. Writable "
                            + "means privileges can be added to a role the caller already holds, which is the "
                            + "same escalation as user administration by a different route."),
            adminSubtree(
                    "/api/v1/security/anonymous/**",
                    "Whether unauthenticated repository access is on at all, and which account it borrows. "
                            + "Pointing it at a privileged account hands that account's rights to everyone "
                            + "who can reach the instance."),
            adminSubtree(
                    "/api/v1/security/ldap/**",
                    "The authentication source itself. A writer can point the instance at an LDAP server "
                            + "they control and log in as anyone, or flip ldapGroupsAsRoles to map a group "
                            + "they own onto nx-admin."),
            adminSubtree(
                    "/api/v1/security/ssl/**",
                    "The TLS truststore. Adding a CA here makes this instance trust the certificates that "
                            + "CA issues, so a writer can make a machine-in-the-middle proxy look legitimate "
                            + "to every outbound proxy-repository fetch."),
            adminSubtree(
                    "/api/v1/security/nvd-firewall/**",
                    "The older NVD-based firewall surface: the vulnerability inventory, the whitelist, and "
                            + "the switch that turns the firewall off. It sits under /api/v1/security for "
                            + "historical reasons rather than under the admin prefix, which is exactly how it "
                            + "escaped notice — no matcher named it, and every logged-in account could read "
                            + "the inventory and disable the control. Same data and same blast radius as the "
                            + "newer firewall surface, so the same role."),
            adminSubtree(
                    "/api/v1/admin/firewall/**",
                    "The repository firewall's admin surface. Enumerates every component in every "
                            + "repository together with the advisories that match it — an inventory of what "
                            + "in this installation is worth attacking — and carries the enforcement switch "
                            + "and the quarantine release."),
            adminSubtree(
                    "/api/v1/admin/**",
                    "Bulk repository import and export, and the Nexus migration runner. The import accepts "
                            + "a YAML document describing repositories to create; the migration executes "
                            + "against a remote Nexus with credentials the caller supplies."),
            adminSubtree(
                    "/api/v1/firewall/exemptions/**",
                    "Everything about an exemption except filing one. The list enumerates what the "
                            + "organisation has decided to let past its own firewall, and approving is the "
                            + "act that lets it past. The POST that files a request is carved out above."),
            adminOnly(
                    HttpMethod.GET,
                    "/api/v1/firewall/rule-types",
                    "The policy editor's rule catalogue. Carries no component data, but it is the "
                            + "supporting call of an administrators-only editor and it enumerates which "
                            + "controls this build actually enforces, which is not a list to hand out. Named "
                            + "by its own matcher because the path is part of the API contract the web UI is "
                            + "written against and cannot move under /api/v1/admin."),
            adminSubtree(
                    "/api/v1/system/**",
                    "Outbound HTTP proxy settings, which carry stored proxy credentials, and license "
                            + "administration. A writer can route all outbound repository traffic through a "
                            + "host of their choosing. The license read is carved out above."),
            adminSubtree(
                    "/api/v1/tasks/**",
                    "Scheduled tasks: the list, the schedule, and the run and stop triggers. POST /{id}/run "
                            + "is why the reads travel with the writes — it needs no body, and the seeded "
                            + "task set includes repository.cleanup and blobstore.compact, so triggering an "
                            + "existing task is enough to start deleting artifacts. The list itself is the "
                            + "instance's maintenance schedule and has no caller outside /admin/tasks."),
            adminSubtree(
                    "/api/v1/blobstores/**",
                    "The storage backends. The read is included for its payload rather than for any write: "
                            + "BlobStoreXO carries the raw config map, and for an S3 store that map holds "
                            + "accessKeyId and secretAccessKey as stored, so listing blob stores handed out "
                            + "the bucket credentials in clear text. DELETE drops a store while its assets "
                            + "stay in the database, orphaning every artifact held in it."),
            adminSubtree(
                    "/api/v1/cleanup-policies/**",
                    "The rules that decide which artifacts a cleanup task removes. Rewriting a policy is a "
                            + "delayed delete: the caller removes nothing, the next scheduled "
                            + "repository.cleanup run does."),
            adminSubtree(
                    "/api/v1/routing-rules/**",
                    "Whether a request may reach a repository's upstream at all. A writer can block or "
                            + "divert resolution for chosen coordinate patterns."),
            adminSubtree(
                    "/api/v1/audit/**",
                    "The audit log and its CSV/JSON export of up to 10,000 rows. Gated for what it contains "
                            + "rather than for any write: every row carries userId, ipAddress and the full "
                            + "artifact path, so the export is a record of who fetched what, when and from "
                            + "where — personal data about the customer's own developers, in a file."),
            adminSubtree(
                    "/api/v1/activity/**",
                    "The live activity feed. Same payload as the audit log — /recent is literally its first "
                            + "page through the same DTO — so it takes the same answer; leaving it open would "
                            + "have made the audit rule a formality. Nothing in the web UI subscribes to "
                            + "either endpoint."),
            adminSubtree(
                    "/api/v1/repositories/*/cache/**",
                    "Proxy cache administration. POST /invalidate/pattern takes a regular expression and .* "
                            + "matches everything, so one call empties a proxy's cache and sends every "
                            + "subsequent build back to the upstream — a denial of service against the build "
                            + "farm, and against an air-gapped installation the permanent loss of the only "
                            + "copy. Listed ahead of the repository rules because the first matching rule "
                            + "wins and this path is the narrower one."),
            adminSubtree(
                    "/api/v1/repositories/*/blacklist/**",
                    "Per-repository blacklists. PUT replaces the whole pattern list, so a single call "
                            + "removes whatever supply-chain blocking the administrator configured, silently "
                            + "and without touching any repository setting the UI would show."),
            adminOnly(
                    HttpMethod.DELETE,
                    "/api/v1/repositories/{name}",
                    "Deleting a repository. Verb-precise because create and update on the same path are the "
                            + "most heavily documented writes in the project — admin-guide.md, the migration "
                            + "guide, test-projects/setup.sh, the upgrade and docker test scripts, two k6 "
                            + "scenarios — and a customer who followed the migration guide has them in a "
                            + "bootstrap job. Deleting a repository appears in no recipe anywhere."),
            adminOnly(
                    HttpMethod.DELETE,
                    "/api/v1/components/{id}",
                    "Deleting a component cascades to its assets and removes their blobs from storage. "
                            + "Verb-precise, and the wildcard segment also spans /api/v1/components/upload, "
                            + "which belongs to a second controller and is the documented publish endpoint "
                            + "for build jobs — a subtree rule here would have locked every one of them out."),
            adminOnly(
                    HttpMethod.DELETE,
                    "/api/v1/assets/{id}",
                    "Deleting an asset removes the backing blob from storage as well as the row. Same "
                            + "verb-precise treatment as the component delete above."),

            // ── AUTHENTICATED_BY_FALLBACK ───────────────────────────────
            //
            // Fifteen endpoints reach the end of the chain and are caught by
            // .requestMatchers("/api/v1/**").authenticated(). None of them is
            // named by a matcher. Each line below is a claim that the endpoint
            // was looked at afterwards and that every account which can log in
            // may have it — not a claim that nobody has got round to it.
            //
            // Two things they have in common are worth stating once. The browse
            // and search surface is what a read-only account exists for, and it
            // is what the dashboard, browse and upload pages of the web UI call
            // for every user. And the repository write surface is reached by the
            // documented CI identity, which admin-guide.md creates as a plain
            // user holding nx-anonymous, not as an administrator.

            authenticatedByFallback(
                    HttpMethod.GET,
                    "/api/v1/components",
                    "Browsing components in a repository. The read surface a plain account is for, and "
                            + "called by the browse page for every logged-in user."),
            authenticatedByFallback(
                    HttpMethod.GET,
                    "/api/v1/components/{id}",
                    "One component's detail. Same surface as the list above; the DELETE on this same path "
                            + "is gated separately."),
            authenticatedByFallback(
                    HttpMethod.GET,
                    "/api/v1/assets",
                    "Asset listing, the level below components in the same browse surface."),
            authenticatedByFallback(
                    HttpMethod.GET,
                    "/api/v1/assets/{id}",
                    "One asset's metadata. Same surface; the DELETE on this path is gated separately."),
            authenticatedByFallback(
                    HttpMethod.GET,
                    "/api/v1/search",
                    "Artifact search. Returns coordinates the same account can already reach through "
                            + "browse, and it backs the search box every logged-in user sees."),
            authenticatedByFallback(
                    HttpMethod.POST,
                    "/api/v1/components/upload",
                    "The documented publish endpoint. admin-guide.md creates a CI user holding "
                            + "nx-anonymous and has it call this after mvn package, npm pack or dotnet pack, "
                            + "and three CI recipes in the docs do the same. Requiring a role here would "
                            + "break every build job written from the documentation."),
            authenticatedByFallback(
                    HttpMethod.GET,
                    "/api/v1/repositories",
                    "The repository list. Called from eight pages of the web UI, three of them an ordinary "
                            + "user's own — dashboard, browse and upload — and from seven scripted call "
                            + "sites."),
            authenticatedByFallback(
                    HttpMethod.GET,
                    "/api/v1/repositories/{name}",
                    "One repository's configuration. Read side of the same surface."),
            authenticatedByFallback(
                    HttpMethod.POST,
                    "/api/v1/repositories",
                    "Creating a repository. Left open deliberately when the operational rules were drawn: "
                            + "this is the single most heavily documented write in the project, and an "
                            + "idempotent bootstrap job written from the migration guide does POST-then-PUT. "
                            + "It is also the entry in this whole file that most deserves a second look — a "
                            + "caller who may create repositories may create a proxy pointing anywhere."),
            authenticatedByFallback(
                    HttpMethod.PUT,
                    "/api/v1/repositories/{name}",
                    "Updating a repository, the other half of that documented bootstrap. Same reservation "
                            + "as the create above."),
            authenticatedByFallback(
                    HttpMethod.GET,
                    "/api/v1/repositories/{name}/members",
                    "The member list of a group repository. Structure the same account can already infer "
                            + "from the repository list."),
            authenticatedByFallback(
                    HttpMethod.PUT,
                    "/api/v1/repositories/{name}/members",
                    "Rewriting a group repository's member list. Travels with the repository update above "
                            + "because it is part of the same documented provisioning; carries the same "
                            + "reservation."),
            authenticatedByFallback(
                    HttpMethod.GET,
                    "/api/v1/metrics",
                    "Instance counters — artifact and repository totals, storage used — as shown on the "
                            + "dashboard every logged-in user opens."),
            authenticatedByFallback(
                    HttpMethod.GET,
                    "/api/v1/metrics/throughput",
                    "The throughput graph on that same dashboard."),
            authenticatedByFallback(
                    HttpMethod.GET,
                    "/api/v1/status/check",
                    "The deeper status probe, next to the public /api/v1/status. Public only up to the "
                            + "exact path /api/v1/status, so this sibling needs credentials — which is the "
                            + "right way round for the one of the pair that reports on subsystems, though "
                            + "the split is a consequence of the exact matcher rather than a decision "
                            + "anybody recorded."));

    private EndpointAuthorizationInventory() {}
}
