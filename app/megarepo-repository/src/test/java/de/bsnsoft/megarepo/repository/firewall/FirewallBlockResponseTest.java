package de.bsnsoft.megarepo.repository.firewall;

import com.github.packageurl.PackageURL;
import de.bsnsoft.megarepo.core.firewall.FirewallAction;
import de.bsnsoft.megarepo.core.firewall.FirewallApiPaths;
import de.bsnsoft.megarepo.core.firewall.FirewallFailMode;
import de.bsnsoft.megarepo.core.firewall.FirewallMode;
import de.bsnsoft.megarepo.core.firewall.FirewallQuarantineReason;
import de.bsnsoft.megarepo.core.firewall.FirewallQuarantineState;
import de.bsnsoft.megarepo.core.firewall.FirewallRuleType;
import de.bsnsoft.megarepo.repository.firewall.identity.ComponentIdentity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a developer sees when a build fails.
 *
 * <p>These assertions are about wording, which is unusual for a test and
 * deliberate here: the 403 body is the entire user interface of a blocked
 * download. If it stops naming the component, the rule or the advisory ids, the
 * feature has regressed in the only way its users can perceive — even though
 * every other test still passes.
 */
class FirewallBlockResponseTest {

    private static final String PATH =
            "org/apache/logging/log4j/log4j-core/2.14.1/log4j-core-2.14.1.jar";
    private static final String KEY = "pkg:maven/org.apache.logging.log4j/log4j-core@2.14.1";
    private static final String ENCODED_KEY =
            "pkg%3Amaven%2Forg.apache.logging.log4j%2Flog4j-core%402.14.1";
    private static final UUID REPO_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String BASE_URL = "https://megarepo.example.com";
    private static final Instant NEXT_LOOK = Instant.parse("2026-08-31T10:00:00Z");

    @Test
    @DisplayName("the plain-text body names the component, the rule, the numbers and the advisories")
    void textBodyIsReadableInABuildLog() {
        String body = FirewallBlockResponse.body(blocked(), false);

        assertThat(body).contains("MegaRepo repository firewall: this download was blocked.");
        assertThat(body).contains("Repository : maven-central");
        assertThat(body).contains("Path       : " + PATH);
        assertThat(body).contains("Component  : pkg:maven/org.apache.logging.log4j/log4j-core@2.14.1");
        assertThat(body).contains("Policy     : Default");
        assertThat(body).contains(
                "- CVSS_THRESHOLD (BLOCK): CVSS 10 is at or above the configured threshold of 9");
        assertThat(body).contains("advisories: CVE-2021-44228, GHSA-jfh8-c2jp-5v3q");
        assertThat(body)
                .as("a developer needs to know what to do next, not only that they were denied")
                .contains("ask your MegaRepo administrator");
    }

    @Test
    @DisplayName("every blocking rule appears, not only the first")
    void allBlockingRulesAreListed() {
        String body = FirewallBlockResponse.body(blockedByBothRules(), false);

        assertThat(body).contains("CVSS_THRESHOLD");
        assertThat(body).contains("KNOWN_MALICIOUS");
        assertThat(body).contains("MAL-2024-1234");
    }

    @Test
    @DisplayName("the JSON body puts the sentence in `error`, which is what npm prints")
    void jsonBodyIsReadableInNpmOutput() {
        String body = FirewallBlockResponse.body(blocked(), true);

        assertThat(body).startsWith("{\"error\":\"MegaRepo firewall blocked "
                + "pkg:maven/org.apache.logging.log4j/log4j-core@2.14.1 from 'maven-central'");
        assertThat(body).contains("\"code\":\"FIREWALL_BLOCKED\"");
        assertThat(body).contains("\"component\":\"pkg:maven/org.apache.logging.log4j/log4j-core@2.14.1\"");
        assertThat(body).contains("\"policy\":\"Default\"");
        assertThat(body).contains("\"advisoryIds\":[\"CVE-2021-44228\",\"GHSA-jfh8-c2jp-5v3q\"]");
        assertThat(body).contains("\"rule\":\"CVSS_THRESHOLD\"");
        assertThat(body).contains("\"action\":\"BLOCK\"");
    }

    @Test
    @DisplayName("the summary survives on its own — it is all a client that hides the body will show")
    void summaryStandsAlone() {
        assertThat(FirewallBlockResponse.summary(blocked())).isEqualTo(
                "MegaRepo firewall blocked pkg:maven/org.apache.logging.log4j/log4j-core@2.14.1 "
                        + "from 'maven-central': CVSS_THRESHOLD — CVSS 10 is at or above the "
                        + "configured threshold of 9. Advisories: CVE-2021-44228, GHSA-jfh8-c2jp-5v3q.");
    }

    @Test
    @DisplayName("headers repeat the rule and the advisories for clients that show no body at all")
    void headersCarryTheSameFacts() {
        Map<String, String> headers = FirewallBlockResponse.headers(blocked());

        assertThat(headers).containsEntry(FirewallBlockResponse.HEADER_FIREWALL, "blocked");
        assertThat(headers).containsEntry(FirewallBlockResponse.HEADER_RULE, "CVSS_THRESHOLD");
        assertThat(headers).containsEntry(
                FirewallBlockResponse.HEADER_ADVISORIES, "CVE-2021-44228,GHSA-jfh8-c2jp-5v3q");
        assertThat(headers.get(FirewallBlockResponse.HEADER_REASON))
                .contains("MegaRepo firewall blocked")
                .doesNotContain("\n")
                .doesNotContain("\r");
    }

    @Test
    @DisplayName("a fail-closed block explains that the component could not be checked, not that it is bad")
    void failClosedBodySaysWhatActuallyHappened() {
        FirewallEvaluation unavailable = new FirewallEvaluation(
                UUID.randomUUID(), "maven-central", PATH,
                new FirewallRepositorySettings(
                        FirewallMode.QUARANTINE, FirewallFailMode.FAIL_CLOSED, null, true),
                null, List.of(), FirewallEvaluation.Outcome.UNAVAILABLE, false,
                FirewallDecision.unavailable(true));

        String body = FirewallBlockResponse.body(unavailable, false);

        assertThat(body).contains("could not check this component in time");
        assertThat(body).contains("FAIL_CLOSED");
        assertThat(body)
                .as("no rule fired, so no rule may be named")
                .doesNotContain("CVSS_THRESHOLD");
        assertThat(FirewallBlockResponse.headers(unavailable))
                .containsEntry(FirewallBlockResponse.HEADER_RULE, "EVALUATION_UNAVAILABLE");
    }

    @Test
    @DisplayName("JSON only when the client asked for it")
    void acceptHeaderChoosesTheShape() {
        assertThat(FirewallBlockResponse.prefersJson("application/json")).isTrue();
        assertThat(FirewallBlockResponse.prefersJson("application/vnd.npm.install-v1+json")).isTrue();
        assertThat(FirewallBlockResponse.prefersJson("*/*")).isFalse();
        assertThat(FirewallBlockResponse.prefersJson(null)).isFalse();
        assertThat(FirewallBlockResponse.contentType(true)).isEqualTo("application/json;charset=UTF-8");
        assertThat(FirewallBlockResponse.contentType(false)).isEqualTo("text/plain;charset=UTF-8");
    }

    @Test
    @DisplayName("a quote in a rule reason cannot break the JSON body")
    void jsonIsEscaped() {
        FirewallEvaluation evaluation = evaluation(FirewallDecision.blocked(
                UUID.randomUUID(), "Policy \"A\"",
                List.of(new FirewallRuleViolation(
                        FirewallRuleType.KNOWN_MALICIOUS, FirewallAction.BLOCK,
                        "advisory \"MAL-1\"\nflags this", List.of("MAL-1")))));

        String body = FirewallBlockResponse.body(evaluation, true);

        assertThat(body).contains("\\\"MAL-1\\\"");
        assertThat(body).contains("\\n");
        assertThat(body).contains("Policy \\\"A\\\"");
    }

    // ------------------------------------------------------------ policy name

    @Test
    @DisplayName("the policy name can be suppressed for an installation whose names mean nothing outside")
    void policyNameIsSuppressible() {
        FirewallBlockResponse.Context anonymous = context(properties()
                .includePolicyName(false)
                .build());

        String text = FirewallBlockResponse.body(blocked(), false, anonymous);
        String json = FirewallBlockResponse.body(blocked(), true, anonymous);

        assertThat(text).doesNotContain("Policy     :").doesNotContain("Default");
        assertThat(json).doesNotContain("\"policy\"");
        assertThat(text)
                .as("suppressing the policy name may not suppress what was blocked and why")
                .contains("CVSS_THRESHOLD")
                .contains("CVSS 10 is at or above the configured threshold of 9");
    }

    // -------------------------------------------------------- advisory links

    @Test
    @DisplayName("an advisory id is rendered as a link to the feed that published it")
    void advisoryIdsBecomeLinks() {
        String text = FirewallBlockResponse.body(blocked(), false, context(properties().build()));
        String json = FirewallBlockResponse.body(blocked(), true, context(properties().build()));

        assertThat(text)
                .as("the id says what is wrong; the link is how a developer decides whether it matters")
                .contains("CVE-2021-44228: https://nvd.nist.gov/vuln/detail/CVE-2021-44228")
                .contains("GHSA-jfh8-c2jp-5v3q: https://github.com/advisories/GHSA-jfh8-c2jp-5v3q");
        assertThat(json).contains("\"advisoryLinks\":{"
                + "\"CVE-2021-44228\":\"https://nvd.nist.gov/vuln/detail/CVE-2021-44228\","
                + "\"GHSA-jfh8-c2jp-5v3q\":\"https://github.com/advisories/GHSA-jfh8-c2jp-5v3q\"}");
    }

    @Test
    @DisplayName("a MAL- id links to OSV; an id from nowhere MegaRepo ingests gets no guessed link")
    void onlyKnownPrefixesAreLinked() {
        assertThat(FirewallBlockResponse.advisoryUrl("MAL-2024-1234"))
                .isEqualTo("https://osv.dev/vulnerability/MAL-2024-1234");
        assertThat(FirewallBlockResponse.advisoryUrl("ACME-2024-1"))
                .as("a link that 404s is worse than no link at all")
                .isNull();
        assertThat(FirewallBlockResponse.advisoryUrl(null)).isNull();

        FirewallEvaluation unknownFeed = evaluation(FirewallDecision.blocked(
                UUID.randomUUID(), "Default",
                List.of(new FirewallRuleViolation(
                        FirewallRuleType.KNOWN_MALICIOUS, FirewallAction.BLOCK,
                        "advisory ACME-2024-1 flags this component as malicious",
                        List.of("ACME-2024-1")))));

        String text = FirewallBlockResponse.body(unknownFeed, false, context(properties().build()));

        assertThat(text).contains("advisories: ACME-2024-1");
        assertThat(text).doesNotContain("ACME-2024-1: http");
    }

    @Test
    @DisplayName("advisory links can be switched off; the ids themselves stay")
    void advisoryLinksAreSuppressible() {
        FirewallBlockResponse.Context noLinks = context(properties()
                .includeAdvisoryLinks(false)
                .build());

        String text = FirewallBlockResponse.body(blocked(), false, noLinks);
        String json = FirewallBlockResponse.body(blocked(), true, noLinks);

        assertThat(text).doesNotContain("https://nvd.nist.gov").doesNotContain("https://github.com");
        assertThat(text).contains("advisories: CVE-2021-44228, GHSA-jfh8-c2jp-5v3q");
        assertThat(json).doesNotContain("\"advisoryLinks\"");
        assertThat(json).contains("\"advisoryIds\":[\"CVE-2021-44228\",\"GHSA-jfh8-c2jp-5v3q\"]");
    }

    // ------------------------------------------------------ exemption request

    @Test
    @DisplayName("the exemption link expands every placeholder, URL-encoded — a purl is full of separators")
    void theExemptionLinkIsExpandedAndEncoded() {
        FirewallBlockResponse.Context context = context(properties()
                .exemptionRequestUrlTemplate(
                        "{baseUrl}/firewall/exemptions/new/{repository}/{componentKey}/{rule}")
                .build());

        String text = FirewallBlockResponse.body(blocked(), false, context);
        String expected = BASE_URL + "/firewall/exemptions/new/maven-central/"
                + ENCODED_KEY + "/CVSS_THRESHOLD";

        assertThat(text).contains("To ask for an exemption:").contains(expected);
        assertThat(FirewallBlockResponse.body(blocked(), true, context))
                .contains("\"exemptionRequest\":{\"url\":\"" + expected + "\"");
        assertThat(FirewallBlockResponse.headers(blocked(), context))
                .as("NuGet's client shows neither body, so the link has to survive in a header too")
                .containsEntry(FirewallBlockResponse.HEADER_EXEMPTION, expected);
    }

    @Test
    @DisplayName("a pinned base URL wins over the one the request implied")
    void aPinnedBaseUrlWins() {
        FirewallBlockResponse.Context behindAProxy = new FirewallBlockResponse.Context(
                null,
                "http://megarepo.internal:8081",
                properties().baseUrl("https://artifacts.example.com/").build());

        assertThat(FirewallBlockResponse.body(blocked(), false, behindAProxy))
                .as("a proxy that rewrites Host would otherwise send the developer to an internal name")
                .contains("https://artifacts.example.com/admin/firewall/exemptions/new")
                .doesNotContain("megarepo.internal");
    }

    @Test
    @DisplayName("the API call beside the link names the three things a requester would otherwise copy by hand")
    void theExemptionSectionNamesTheApiCall() {
        String text = FirewallBlockResponse.body(blocked(), false, context(properties().build()));

        // Built from the constant rather than written out again: the whole
        // reason FirewallApiPaths exists is that a 403 telling a developer to
        // POST to an endpoint renamed a release ago is discovered by the
        // developer and not by a test.
        assertThat(text).contains("or POST " + BASE_URL + FirewallApiPaths.EXEMPTIONS
                + " with componentKey=" + KEY
                + ", ruleType=CVSS_THRESHOLD"
                + ", repositoryId=" + REPO_ID);
        assertThat(FirewallBlockResponse.body(blocked(), true, context(properties().build())))
                .contains("\"api\":\"" + BASE_URL + FirewallApiPaths.EXEMPTIONS + "\"")
                .contains("\"method\":\"POST\"")
                .contains("\"componentKey\":\"" + KEY + "\"")
                .contains("\"ruleType\":\"CVSS_THRESHOLD\"")
                .contains("\"repositoryId\":\"" + REPO_ID + "\"");
        assertThat(FirewallApiPaths.EXEMPTIONS)
                .as("the constant is the one place the path is written; renaming it moves body and controller together")
                .isEqualTo("/api/v1/firewall/exemptions");
    }

    @Test
    @DisplayName("a blank template suppresses the whole section, link and API call alike")
    void aBlankTemplateSuppressesTheSection() {
        FirewallBlockResponse.Context noSelfService = context(properties()
                .exemptionRequestUrlTemplate("")
                .build());

        String text = FirewallBlockResponse.body(blocked(), false, noSelfService);
        String json = FirewallBlockResponse.body(blocked(), true, noSelfService);

        assertThat(text)
                .as("offering a form nobody may use is worse than offering none")
                .doesNotContain("To ask for an exemption")
                .doesNotContain(FirewallApiPaths.EXEMPTIONS);
        assertThat(json).doesNotContain("\"exemptionRequest\"");
        assertThat(FirewallBlockResponse.headers(blocked(), noSelfService))
                .doesNotContainKey(FirewallBlockResponse.HEADER_EXEMPTION);
    }

    // ------------------------------------------------------- contact message

    @Test
    @DisplayName("the configured contact sentence is appended, never a replacement for the explanation")
    void theContactMessageIsAppended() {
        FirewallBlockResponse.Context configured = context(properties()
                .contactMessage("Questions? #platform-security on Slack, or PLAT-SEC in Jira.")
                .build());

        String text = FirewallBlockResponse.body(blocked(), false, configured);

        assertThat(text)
                .as("no configuration may produce a 403 that fails to say what was blocked and why")
                .contains("this download was blocked")
                .contains("Component  : " + KEY)
                .contains("CVSS_THRESHOLD (BLOCK): CVSS 10 is at or above the configured threshold of 9");
        assertThat(text).endsWith("Questions? #platform-security on Slack, or PLAT-SEC in Jira.\n");
        assertThat(FirewallBlockResponse.body(blocked(), true, configured))
                .contains("\"contact\":\"Questions? #platform-security on Slack, or PLAT-SEC in Jira.\"");
    }

    @Test
    @DisplayName("the contact sentence reaches a fail-closed body as well, which is where it is needed most")
    void theContactMessageReachesTheUnavailableBody() {
        FirewallEvaluation unavailable = new FirewallEvaluation(
                REPO_ID, "maven-central", PATH,
                new FirewallRepositorySettings(
                        FirewallMode.QUARANTINE, FirewallFailMode.FAIL_CLOSED, null, true),
                null, List.of(), FirewallEvaluation.Outcome.UNAVAILABLE, false,
                FirewallDecision.unavailable(true));

        String text = FirewallBlockResponse.body(unavailable, false, context(properties()
                .contactMessage("Ask the platform team.")
                .build()));

        assertThat(text).contains("could not check this component in time");
        assertThat(text).endsWith("Ask the platform team.\n");
    }

    // -------------------------------------------------------------- held

    @Test
    @DisplayName("a held component is told it is waiting, why, and until when — in text")
    void theTextBodyExplainsAHold() {
        String text = FirewallBlockResponse.body(held(), false, context(properties().build()));

        assertThat(text).contains("MegaRepo repository firewall: this download is being held.");
        assertThat(text).contains(
                "Held       : quarantined because it is newer than this repository's policy allows");
        assertThat(text)
                .as("'it will be looked at in eleven minutes' is the difference between waiting and a ticket")
                .contains("Next check : " + NEXT_LOOK);
        assertThat(text).contains("released as soon as the reason above no longer applies");
        assertThat(text)
                .as("a held download is not the same statement as a refused one")
                .doesNotContain("this download was blocked");
    }

    @Test
    @DisplayName("the same hold in JSON, as fields a tool can branch on")
    void theJsonBodyExplainsAHold() {
        String json = FirewallBlockResponse.body(held(), true, context(properties().build()));

        assertThat(json).contains("\"decision\":\"QUARANTINED\"");
        assertThat(json).contains("\"quarantined\":true");
        assertThat(json).contains("\"quarantine\":{\"state\":\"QUARANTINED\","
                + "\"reason\":\"MIN_AGE_NOT_MET\","
                + "\"nextEvaluationAt\":\"" + NEXT_LOOK + "\","
                + "\"hitCount\":4}");
    }

    @Test
    @DisplayName("the quarantine header carries state, reason and next look for a client that shows no body")
    void theQuarantineHeaderCarriesTheHold() {
        Map<String, String> headers = FirewallBlockResponse.headers(held(), context(properties().build()));

        assertThat(headers).containsEntry(
                FirewallBlockResponse.HEADER_QUARANTINE,
                "QUARANTINED:MIN_AGE_NOT_MET;next=" + NEXT_LOOK);
        assertThat(headers)
                .as("the 'was this the firewall?' marker must not change kind for a held component")
                .containsEntry(FirewallBlockResponse.HEADER_FIREWALL, "blocked");
        assertThat(headers.get(FirewallBlockResponse.HEADER_REASON))
                .contains("is holding")
                .contains("It will be checked again at " + NEXT_LOOK);
    }

    @Test
    @DisplayName("a hold decided by an entry already on file names the reason, not a rule that did not run")
    void aShortCircuitedHoldNamesOnlyTheReason() {
        FirewallEvaluation reHeld = evaluation(FirewallDecision.quarantined(
                null, null, List.of(),
                new FirewallDecision.Hold(
                        UUID.randomUUID(), FirewallQuarantineState.QUARANTINED,
                        FirewallQuarantineReason.UNKNOWN_COMPONENT, NEXT_LOOK, 12)));

        String summary = FirewallBlockResponse.summary(reHeld);

        assertThat(summary).isEqualTo(
                "MegaRepo firewall is holding " + KEY + " from 'maven-central': quarantined because "
                        + "nothing is known about this component yet. It will be checked again at "
                        + NEXT_LOOK + ".");
        assertThat(FirewallBlockResponse.headers(reHeld))
                .containsEntry(FirewallBlockResponse.HEADER_RULE, "QUARANTINED");
    }

    @Test
    @DisplayName("an entry moved to BLOCKED is not promised another look")
    void aBlockedEntryPromisesNoNextLook() {
        FirewallEvaluation refused = evaluation(FirewallDecision.quarantined(
                null, null, List.of(),
                new FirewallDecision.Hold(
                        UUID.randomUUID(), FirewallQuarantineState.BLOCKED,
                        FirewallQuarantineReason.POLICY_VIOLATION, NEXT_LOOK, 12)));

        assertThat(FirewallBlockResponse.summary(refused))
                .as("BLOCKED is not re-evaluated hoping for a different answer")
                .doesNotContain("It will be checked again")
                .contains("blocked in the quarantine queue by policy");
    }

    // ------------------------------------------------------------------

    /** Defaults, with a base URL the request implied. */
    private static FirewallBlockResponse.Context context(FirewallBlockProperties properties) {
        return new FirewallBlockResponse.Context(null, BASE_URL + "/", properties);
    }

    private static PropertiesBuilder properties() {
        return new PropertiesBuilder();
    }

    /**
     * The block-body configuration, one setting at a time.
     *
     * <p>{@link FirewallBlockProperties} has five components and each test varies
     * one of them; a builder keeps the assertion about the setting under test
     * rather than about argument order.
     */
    private static final class PropertiesBuilder {

        private boolean includePolicyName = true;
        private boolean includeAdvisoryLinks = true;
        private String exemptionRequestUrlTemplate =
                FirewallBlockProperties.defaults().exemptionRequestUrlTemplate();
        private String baseUrl = "";
        private String contactMessage = "";

        PropertiesBuilder includePolicyName(boolean value) {
            this.includePolicyName = value;
            return this;
        }

        PropertiesBuilder includeAdvisoryLinks(boolean value) {
            this.includeAdvisoryLinks = value;
            return this;
        }

        PropertiesBuilder exemptionRequestUrlTemplate(String value) {
            this.exemptionRequestUrlTemplate = value;
            return this;
        }

        PropertiesBuilder baseUrl(String value) {
            this.baseUrl = value;
            return this;
        }

        PropertiesBuilder contactMessage(String value) {
            this.contactMessage = value;
            return this;
        }

        FirewallBlockProperties build() {
            return new FirewallBlockProperties(includePolicyName, includeAdvisoryLinks,
                    exemptionRequestUrlTemplate, baseUrl, contactMessage);
        }
    }

    private static FirewallEvaluation held() {
        return evaluation(FirewallDecision.quarantined(
                UUID.randomUUID(), "Default",
                List.of(new FirewallRuleViolation(
                        FirewallRuleType.MIN_AGE, FirewallAction.BLOCK,
                        "published 2 hours ago, less than the minimum age of 7 days required by the policy",
                        List.of())),
                new FirewallDecision.Hold(
                        UUID.randomUUID(), FirewallQuarantineState.QUARANTINED,
                        FirewallQuarantineReason.MIN_AGE_NOT_MET, NEXT_LOOK, 4)));
    }

    private static FirewallEvaluation blocked() {
        return evaluation(FirewallDecision.blocked(
                UUID.randomUUID(), "Default",
                List.of(new FirewallRuleViolation(
                        FirewallRuleType.CVSS_THRESHOLD, FirewallAction.BLOCK,
                        "CVSS 10 is at or above the configured threshold of 9",
                        List.of("CVE-2021-44228", "GHSA-jfh8-c2jp-5v3q")))));
    }

    private static FirewallEvaluation blockedByBothRules() {
        return evaluation(FirewallDecision.blocked(
                UUID.randomUUID(), "Default",
                List.of(
                        new FirewallRuleViolation(
                                FirewallRuleType.CVSS_THRESHOLD, FirewallAction.BLOCK,
                                "CVSS 10 is at or above the configured threshold of 9",
                                List.of("CVE-2021-44228")),
                        new FirewallRuleViolation(
                                FirewallRuleType.KNOWN_MALICIOUS, FirewallAction.BLOCK,
                                "advisory MAL-2024-1234 flags this component as malicious",
                                List.of("MAL-2024-1234")))));
    }

    private static FirewallEvaluation evaluation(FirewallDecision decision) {
        return new FirewallEvaluation(
                REPO_ID, "maven-central", PATH,
                new FirewallRepositorySettings(
                        FirewallMode.QUARANTINE, FirewallFailMode.FAIL_OPEN, null, true),
                identity(), List.of(), FirewallEvaluation.Outcome.MATCHED, false, decision);
    }

    private static ComponentIdentity identity() {
        try {
            return new ComponentIdentity.Purl(new PackageURL(
                    "maven", "org.apache.logging.log4j", "log4j-core", "2.14.1", null, null));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
