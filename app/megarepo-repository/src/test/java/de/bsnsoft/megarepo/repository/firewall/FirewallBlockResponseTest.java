package de.bsnsoft.megarepo.repository.firewall;

import com.github.packageurl.PackageURL;
import de.bsnsoft.megarepo.core.firewall.FirewallAction;
import de.bsnsoft.megarepo.core.firewall.FirewallFailMode;
import de.bsnsoft.megarepo.core.firewall.FirewallMode;
import de.bsnsoft.megarepo.core.firewall.FirewallRuleType;
import de.bsnsoft.megarepo.repository.firewall.identity.ComponentIdentity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
                UUID.randomUUID(), "maven-central", PATH,
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
