package de.bsnsoft.megarepo.format.pypi.simple;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Rewrites download URLs in PyPI simple index HTML to point through MegaRepo
 * instead of directly to upstream CDN (files.pythonhosted.org).
 */
@Component
public class PypiProxyUrlRewriter {

    private static final Pattern HREF_PATTERN = Pattern.compile(
            "href=\"(https?://[^\"]*?/packages/[^\"]*?)\"",
            Pattern.CASE_INSENSITIVE
    );

    // PEP 658 metadata attributes — pip uses these to request .whl.metadata files
    // which MegaRepo doesn't serve. Stripping them makes pip fall back to downloading the wheel.
    private static final Pattern PEP658_ATTR_PATTERN = Pattern.compile(
            "\\s+data-(?:dist-info-metadata|core-metadata)=\"[^\"]*\"",
            Pattern.CASE_INSENSITIVE
    );

    public record RewriteResult(String rewrittenHtml, Map<String, String> urlMappings) {}

    /**
     * Rewrites absolute upstream download URLs to relative paths through MegaRepo.
     * Example: href="https://files.pythonhosted.org/packages/.../requests-2.33.0.tar.gz"
     * becomes: href="../../packages/requests-2.33.0.tar.gz"
     */
    public RewriteResult rewrite(String html) {
        Map<String, String> mappings = new HashMap<>();
        Matcher matcher = HREF_PATTERN.matcher(html);
        StringBuilder sb = new StringBuilder();

        while (matcher.find()) {
            String upstreamUrl = matcher.group(1);
            String filename = extractFilename(upstreamUrl);
            if (filename != null) {
                mappings.put(filename, upstreamUrl);
                matcher.appendReplacement(sb, "href=\"../../packages/" + filename + "\"");
            }
        }
        matcher.appendTail(sb);

        // Strip PEP 658 metadata attributes so pip doesn't try to fetch .whl.metadata
        String result = PEP658_ATTR_PATTERN.matcher(sb.toString()).replaceAll("");

        return new RewriteResult(result, mappings);
    }

    private String extractFilename(String url) {
        int lastSlash = url.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < url.length() - 1) {
            String filename = url.substring(lastSlash + 1);
            // Strip hash fragment (#sha256=...)
            int hashIdx = filename.indexOf('#');
            if (hashIdx > 0) {
                filename = filename.substring(0, hashIdx);
            }
            return filename;
        }
        return null;
    }
}
