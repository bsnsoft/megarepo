package de.bsnsoft.megarepo.repository.firewall;

/**
 * Who asked for the component, recorded alongside the finding.
 *
 * <p>Deliberately a handful of already-extracted strings rather than the
 * {@code HttpServletRequest}: the evaluation runs on a pool thread after the
 * response has been written, and by then the request and its attributes may
 * already have been recycled by the container. Copying the four values the
 * audit trail needs at hook time makes that impossible to get wrong.
 *
 * @param user authenticated principal, or {@code "anonymous"}
 * @param clientIp remote address, X-Forwarded-For aware
 * @param path artifact path within the repository
 * @param method HTTP method — {@code GET} or {@code HEAD}
 * @param viaRepository the group repository the client actually addressed, when
 *     the download was routed through one; null for a direct download. The
 *     violation is attributed to the member that holds the component — that is
 *     where the operator can act on it — and this is what makes the row also
 *     answer "how did a consumer reach it?", which for this customer is usually
 *     "through the group everyone's settings.xml points at". Kept here rather
 *     than in a column: {@code firewall_violation.request_context} is JSONB and
 *     already carries the rest of the request, so recording it needs no
 *     migration.
 */
public record FirewallRequestContext(
        String user, String clientIp, String path, String method, String viaRepository) {

    /** A direct download — no group in front of it. */
    public FirewallRequestContext(String user, String clientIp, String path, String method) {
        this(user, clientIp, path, method, null);
    }
}
