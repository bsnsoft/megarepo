package de.bsnsoft.megarepo.format.nuget.proxy;

import de.bsnsoft.megarepo.format.nuget.proxy.UpstreamServiceIndexResolver.UpstreamResources;
import org.springframework.stereotype.Component;

/**
 * Rewrites upstream resource URLs inside proxied registration/search JSON so
 * the dotnet client keeps talking to MegaRepo instead of jumping directly to
 * the upstream feed. Plain string replacement is sufficient: the JSON bodies
 * reference resources only via their absolute base URLs, which we know from
 * the upstream service index. Catalog URLs (api.nuget.org/v3/catalog0/…) are
 * intentionally left untouched — clients do not need them for restore/search.
 */
@Component
public class NugetProxyUrlRewriter {

    public String rewrite(String json, UpstreamResources upstream, String repoBase) {
        String result = json;
        if (upstream.flatContainerBase() != null) {
            result = result.replace(upstream.flatContainerBase() + "/", repoBase + "/v3-flatcontainer/");
        }
        if (upstream.registrationsBase() != null) {
            result = result.replace(upstream.registrationsBase() + "/", repoBase + "/v3/registrations/");
        }
        if (upstream.searchBase() != null) {
            result = result.replace(upstream.searchBase() + "?", repoBase + "/v3/search?");
            result = result.replace(upstream.searchBase() + "/", repoBase + "/v3/search/");
        }
        return result;
    }
}
