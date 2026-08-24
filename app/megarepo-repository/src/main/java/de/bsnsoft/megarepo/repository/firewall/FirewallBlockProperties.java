package de.bsnsoft.megarepo.repository.firewall;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Deployment-side configuration of what a blocked download is told.
 *
 * <p>The reader of a firewall 403 is not looking at MegaRepo. They are looking at
 * {@code mvn package} failing in a CI log, and Phase 1 already made that body
 * name the component, the rule and the advisories. Phase 2 adds the two things
 * the customer asked for on top: the policy that refused it, and a link that
 * starts an exemption request — so the next step after a block is a request
 * rather than a Slack message to whoever owns the repository manager.
 *
 * @param includePolicyName whether the policy's name appears in the body. On by
 *     default. Off for an installation whose policy names are internal
 *     ("Q3-audit-finding-14") and mean nothing to the developer reading them
 * @param includeAdvisoryLinks whether advisory ids are rendered as URLs as well
 *     as ids. On by default: the id is the answer to "what is wrong", the link is
 *     the answer to "is it wrong for me"
 * @param exemptionRequestUrlTemplate where the block body sends a developer who
 *     wants an exemption. Supports {@code {baseUrl}}, {@code {repository}},
 *     {@code {componentKey}} and {@code {rule}} placeholders; blank suppresses the
 *     link entirely, which is the right setting when exemption self-service is
 *     off. The default points at the Web UI's exemption request form
 * @param baseUrl the externally reachable base URL of this MegaRepo, used to
 *     expand {@code {baseUrl}}. Blank means "work it out from the request", which
 *     is right for a simple deployment and wrong behind a proxy that rewrites the
 *     Host header — which is why it can be pinned
 * @param contactMessage a free-text sentence appended to the body, for the
 *     administrator who wants to name a team or a ticket queue. The customer
 *     asked for a configurable message; this is it, and it is <em>appended</em>
 *     rather than replacing the generated text so no configuration can produce a
 *     403 that fails to say what was blocked and why
 */
@ConfigurationProperties(prefix = "megarepo.firewall.block")
public record FirewallBlockProperties(
        @DefaultValue("true") boolean includePolicyName,
        @DefaultValue("true") boolean includeAdvisoryLinks,
        @DefaultValue("{baseUrl}/admin/firewall/exemptions/new?component={componentKey}&repository={repository}")
        String exemptionRequestUrlTemplate,
        @DefaultValue("") String baseUrl,
        @DefaultValue("") String contactMessage) {

    public FirewallBlockProperties {
        exemptionRequestUrlTemplate =
                exemptionRequestUrlTemplate == null ? "" : exemptionRequestUrlTemplate.trim();
        baseUrl = baseUrl == null ? "" : stripTrailingSlash(baseUrl.trim());
        contactMessage = contactMessage == null ? "" : contactMessage.trim();
    }

    /** Defaults — the shape a deployment that never configured block bodies gets. */
    public static FirewallBlockProperties defaults() {
        return new FirewallBlockProperties(
                true,
                true,
                "{baseUrl}/admin/firewall/exemptions/new?component={componentKey}&repository={repository}",
                "",
                "");
    }

    /** Whether a block body should offer an exemption-request link at all. */
    public boolean offersExemptionRequests() {
        return !exemptionRequestUrlTemplate.isEmpty();
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
