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
 */
public record FirewallRequestContext(String user, String clientIp, String path, String method) {}
