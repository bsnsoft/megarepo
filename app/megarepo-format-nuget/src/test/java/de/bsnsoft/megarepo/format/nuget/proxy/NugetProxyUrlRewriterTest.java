package de.bsnsoft.megarepo.format.nuget.proxy;

import de.bsnsoft.megarepo.format.nuget.proxy.UpstreamServiceIndexResolver.UpstreamResources;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NugetProxyUrlRewriterTest {

    private final NugetProxyUrlRewriter rewriter = new NugetProxyUrlRewriter();

    private static final UpstreamResources UPSTREAM = new UpstreamResources(
            "https://api.nuget.org/v3-flatcontainer",
            "https://api.nuget.org/v3/registration5-gz-semver2",
            "https://azuresearch-usnc.nuget.org/query",
            "https://azuresearch-usnc.nuget.org/autocomplete");

    private static final String REPO_BASE = "https://repo.example.com/repository/nuget-proxy";

    @Test
    void rewrite_replacesFlatContainerAndRegistrationUrls() {
        String json = """
                {
                  "packageContent": "https://api.nuget.org/v3-flatcontainer/serilog/3.1.1/serilog.3.1.1.nupkg",
                  "registration": "https://api.nuget.org/v3/registration5-gz-semver2/serilog/index.json"
                }
                """;

        String rewritten = rewriter.rewrite(json, UPSTREAM, REPO_BASE);

        assertTrue(rewritten.contains(REPO_BASE + "/v3-flatcontainer/serilog/3.1.1/serilog.3.1.1.nupkg"));
        assertTrue(rewritten.contains(REPO_BASE + "/v3/registrations/serilog/index.json"));
        assertFalse(rewritten.contains("api.nuget.org"));
    }

    @Test
    void rewrite_replacesSearchUrls() {
        String json = "{\"@id\":\"https://azuresearch-usnc.nuget.org/query?q=serilog\"}";
        String rewritten = rewriter.rewrite(json, UPSTREAM, REPO_BASE);
        assertEquals("{\"@id\":\"" + REPO_BASE + "/v3/search?q=serilog\"}", rewritten);
    }

    @Test
    void rewrite_leavesCatalogUrlsAlone() {
        String json = "{\"catalog\":\"https://api.nuget.org/v3/catalog0/data/page1.json\"}";
        assertEquals(json, rewriter.rewrite(json, UPSTREAM, REPO_BASE));
    }

    @Test
    void rewrite_handlesNullResourceBases() {
        var upstream = new UpstreamResources("https://api.nuget.org/v3-flatcontainer", null, null, null);
        String json = "{\"x\":\"https://api.nuget.org/v3-flatcontainer/a/1.0.0/a.1.0.0.nupkg\"}";
        String rewritten = rewriter.rewrite(json, upstream, REPO_BASE);
        assertTrue(rewritten.contains(REPO_BASE + "/v3-flatcontainer/a/1.0.0/a.1.0.0.nupkg"));
    }
}
