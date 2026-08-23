package de.bsnsoft.megarepo.format.npm.proxy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the packument rewriting that makes an npm proxy actually proxy package downloads
 * instead of handing clients the upstream URL (GitHub issue #1).
 */
class NpmProxyUrlRewriterTest {

    private static final String REMOTE = "https://registry.npmjs.org";
    private static final String REPO_BASE = "https://repo.example.com/repository/npm-proxy";

    private NpmProxyUrlRewriter rewriter;

    @BeforeEach
    void setUp() {
        rewriter = new NpmProxyUrlRewriter();
    }

    private String rewrite(String json) {
        return new String(
                rewriter.rewrite(json.getBytes(StandardCharsets.UTF_8), REMOTE, REPO_BASE).content(),
                StandardCharsets.UTF_8);
    }

    @Test
    void rewritesUnscopedTarballUrlToRepository() {
        String json =
                "{\"name\":\"lodash\",\"versions\":{\"4.17.21\":{\"dist\":{"
                        + "\"tarball\":\"https://registry.npmjs.org/lodash/-/lodash-4.17.21.tgz\"}}}}";

        String result = rewrite(json);

        assertTrue(result.contains(
                "\"tarball\":\"" + REPO_BASE + "/lodash/-/lodash-4.17.21.tgz\""));
        assertFalse(result.contains("registry.npmjs.org"));
    }

    @Test
    void rewritesScopedTarballUrlToRepository() {
        String json =
                "{\"name\":\"@types/node\",\"versions\":{\"20.0.0\":{\"dist\":{"
                        + "\"tarball\":\"https://registry.npmjs.org/@types/node/-/node-20.0.0.tgz\"}}}}";

        String result = rewrite(json);

        assertTrue(result.contains("\"tarball\":\"" + REPO_BASE + "/@types/node/-/node-20.0.0.tgz\""));
    }

    @Test
    void rewritesEveryVersionInThePackument() {
        String json =
                "{\"versions\":{"
                        + "\"1.0.0\":{\"dist\":{\"tarball\":\"https://registry.npmjs.org/p/-/p-1.0.0.tgz\"}},"
                        + "\"2.0.0\":{\"dist\":{\"tarball\":\"https://registry.npmjs.org/p/-/p-2.0.0.tgz\"}},"
                        + "\"3.0.0\":{\"dist\":{\"tarball\":\"https://registry.npmjs.org/p/-/p-3.0.0.tgz\"}}}}";

        NpmProxyUrlRewriter.RewriteResult result =
                rewriter.rewrite(json.getBytes(StandardCharsets.UTF_8), REMOTE, REPO_BASE);

        assertEquals(3, result.rewrittenCount());
        assertFalse(new String(result.content(), StandardCharsets.UTF_8).contains("registry.npmjs.org"));
    }

    /**
     * A registry that offloads downloads to a separate CDN cannot be reconstructed from
     * {@code remoteUrl + path}, so those URLs are left alone rather than rewritten into a
     * link MegaRepo could not resolve.
     */
    @Test
    void leavesForeignHostTarballUrlsUntouched() {
        String json = "{\"dist\":{\"tarball\":\"https://cdn.example.net/files/pkg-1.0.0.tgz\"}}";

        NpmProxyUrlRewriter.RewriteResult result =
                rewriter.rewrite(json.getBytes(StandardCharsets.UTF_8), REMOTE, REPO_BASE);

        assertEquals(0, result.rewrittenCount());
        assertTrue(new String(result.content(), StandardCharsets.UTF_8)
                .contains("https://cdn.example.net/files/pkg-1.0.0.tgz"));
    }

    @Test
    void toleratesWhitespaceAroundTheJsonSeparator() {
        String json = "{\"dist\":{\"tarball\" : \"https://registry.npmjs.org/a/-/a-1.0.0.tgz\"}}";

        assertTrue(rewrite(json).contains("\"tarball\":\"" + REPO_BASE + "/a/-/a-1.0.0.tgz\""));
    }

    @Test
    void handlesTrailingSlashesOnConfiguredUrls() {
        String json = "{\"dist\":{\"tarball\":\"https://registry.npmjs.org/a/-/a-1.0.0.tgz\"}}";

        String result = new String(
                rewriter.rewrite(
                                json.getBytes(StandardCharsets.UTF_8),
                                "https://registry.npmjs.org/",
                                REPO_BASE + "/")
                        .content(),
                StandardCharsets.UTF_8);

        assertTrue(result.contains("\"tarball\":\"" + REPO_BASE + "/a/-/a-1.0.0.tgz\""));
    }

    @Test
    void leavesOtherFieldsIntact() {
        String json =
                "{\"name\":\"pkg\",\"homepage\":\"https://registry.npmjs.org/pkg\","
                        + "\"dist\":{\"tarball\":\"https://registry.npmjs.org/pkg/-/pkg-1.0.0.tgz\","
                        + "\"shasum\":\"abc123\"}}";

        String result = rewrite(json);

        // Only dist.tarball is a download pointer; everything else stays as upstream sent it.
        assertTrue(result.contains("\"homepage\":\"https://registry.npmjs.org/pkg\""));
        assertTrue(result.contains("\"shasum\":\"abc123\""));
        assertTrue(result.contains("\"tarball\":\"" + REPO_BASE + "/pkg/-/pkg-1.0.0.tgz\""));
    }

    @Test
    void emptyDocumentIsReturnedUnchanged() {
        NpmProxyUrlRewriter.RewriteResult result =
                rewriter.rewrite("{}".getBytes(StandardCharsets.UTF_8), REMOTE, REPO_BASE);

        assertEquals(0, result.rewrittenCount());
        assertEquals("{}", new String(result.content(), StandardCharsets.UTF_8));
    }
}
