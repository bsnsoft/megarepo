package de.bsnsoft.megarepo.format.npm.proxy;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites {@code dist.tarball} URLs in an upstream npm packument so that package downloads
 * are routed back through MegaRepo instead of going straight to the upstream registry.
 *
 * <p>Without this step a proxy repository caches only the metadata document. The tarball URLs
 * inside it still point at (for example) {@code https://registry.npmjs.org/...}, so npm and pnpm
 * download every package directly from upstream. Nothing is cached, no components are created,
 * and the Browse tree stays empty — the behaviour reported in GitHub issue #1.
 *
 * <p>Only URLs that live under the repository's configured {@code remoteUrl} are rewritten.
 * Their local path is then simply the remainder of the URL, which means the upstream URL can be
 * reconstructed on demand as {@code remoteUrl + "/" + path} and no per-version bookkeeping is
 * needed. That matters: a packument such as {@code @typescript-eslint/parser} carries several
 * thousand versions, so persisting a row per tarball (the approach the PyPI proxy can afford for
 * its much smaller indexes) would be far too heavy here.
 *
 * <p>Tarball URLs pointing somewhere else — a registry that offloads downloads to a separate CDN —
 * are deliberately left untouched, so such a setup keeps working exactly as it did before.
 */
@Component
public class NpmProxyUrlRewriter {

    /**
     * Matches the {@code "tarball": "<url>"} member of a {@code dist} object. npm registries emit
     * plain JSON with no escaped characters inside these URLs, so a targeted regex is both correct
     * and far cheaper than parsing a multi-megabyte document into a tree.
     */
    private static final Pattern TARBALL_PATTERN =
            Pattern.compile("\"tarball\"\\s*:\\s*\"([^\"\\\\]*)\"");

    /**
     * The outcome of a rewrite.
     *
     * @param content        the rewritten packument
     * @param rewrittenCount how many tarball URLs were redirected through MegaRepo
     */
    public record RewriteResult(byte[] content, int rewrittenCount) {}

    /**
     * Rewrites every tarball URL that lives under {@code remoteUrl} to the equivalent MegaRepo URL.
     *
     * @param packument the raw upstream metadata document
     * @param remoteUrl the proxy repository's upstream base URL, without a trailing slash
     * @param repoBase  MegaRepo's own base for this repository, e.g.
     *                  {@code https://repo.example.com/repository/npm-proxy}, without a trailing slash
     */
    public RewriteResult rewrite(byte[] packument, String remoteUrl, String repoBase) {
        String json = new String(packument, StandardCharsets.UTF_8);
        String upstreamPrefix = stripTrailingSlash(remoteUrl) + "/";
        String localPrefix = stripTrailingSlash(repoBase) + "/";

        Matcher matcher = TARBALL_PATTERN.matcher(json);
        StringBuilder rewritten = new StringBuilder(json.length());
        int count = 0;

        while (matcher.find()) {
            String upstreamUrl = matcher.group(1);
            String replacement;
            if (startsWithIgnoreCase(upstreamUrl, upstreamPrefix)) {
                String path = upstreamUrl.substring(upstreamPrefix.length());
                replacement = "\"tarball\":\"" + localPrefix + path + "\"";
                count++;
            } else {
                // Not served by the configured upstream (e.g. a separate download CDN):
                // leave the client pointing at the original location.
                replacement = matcher.group();
            }
            matcher.appendReplacement(rewritten, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(rewritten);

        return new RewriteResult(rewritten.toString().getBytes(StandardCharsets.UTF_8), count);
    }

    private static String stripTrailingSlash(String url) {
        if (url == null) {
            return "";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static boolean startsWithIgnoreCase(String value, String prefix) {
        return value.regionMatches(true, 0, prefix, 0, prefix.length());
    }
}
