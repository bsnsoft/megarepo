package de.bsnsoft.megarepo.repository.nvd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * HTTP client for the NVD CVE API 2.0. Paginated fetch, optional delta window,
 * and structured parsing of CVSS metrics and CPE criteria. No caching here —
 * caller owns persistence.
 */
@Component
public class NvdApiClient {

    private static final Logger log = LoggerFactory.getLogger(NvdApiClient.class);
    public static final String NVD_URL = "https://services.nvd.nist.gov/rest/json/cves/2.0";
    public static final int DEFAULT_PAGE_SIZE = 2000;
    public static final int MAX_PAGE_SIZE = 2000;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    @Autowired
    public NvdApiClient(ObjectMapper objectMapper) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build(), objectMapper, NVD_URL);
    }

    // For tests
    public NvdApiClient(HttpClient httpClient, ObjectMapper objectMapper, String baseUrl) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
    }

    public record CveData(
            String cveId,
            Instant published,
            Instant lastModified,
            double cvssScore,
            String cvssVersion,
            String severity,
            String description,
            List<CpeMatch> cpeMatches) {}

    public record CpeMatch(
            String vendor,
            String product,
            String versionExact,
            String versionStartIncluding,
            String versionStartExcluding,
            String versionEndIncluding,
            String versionEndExcluding) {}

    public record PageResult(int totalResults, int startIndex, int resultsPerPage, List<CveData> cves) {}

    public PageResult fetchPage(int startIndex, int pageSize, Instant lastModStart, Instant lastModEnd, String apiKey)
            throws IOException, InterruptedException {
        StringBuilder url = new StringBuilder(baseUrl);
        url.append("?startIndex=").append(startIndex);
        url.append("&resultsPerPage=").append(Math.min(pageSize, MAX_PAGE_SIZE));
        if (lastModStart != null && lastModEnd != null) {
            url.append("&lastModStartDate=").append(URLEncoder.encode(
                    DateTimeFormatter.ISO_INSTANT.format(lastModStart), StandardCharsets.UTF_8));
            url.append("&lastModEndDate=").append(URLEncoder.encode(
                    DateTimeFormatter.ISO_INSTANT.format(lastModEnd), StandardCharsets.UTF_8));
        }

        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url.toString()))
                .timeout(Duration.ofSeconds(60))
                .GET();
        if (apiKey != null && !apiKey.isBlank()) {
            reqBuilder.header("apiKey", apiKey);
        }

        HttpResponse<String> response = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 403 || response.statusCode() == 404) {
            throw new IOException("NVD API rejected request (HTTP " + response.statusCode() + "): "
                    + truncate(response.body(), 300));
        }
        if (response.statusCode() != 200) {
            throw new IOException("NVD API returned HTTP " + response.statusCode() + ": "
                    + truncate(response.body(), 300));
        }

        return parseResponse(response.body());
    }

    PageResult parseResponse(String body) throws IOException {
        JsonNode root = objectMapper.readTree(body);
        int total = root.path("totalResults").asInt(0);
        int startIdx = root.path("startIndex").asInt(0);
        int perPage = root.path("resultsPerPage").asInt(0);

        List<CveData> cves = new ArrayList<>();
        JsonNode vulns = root.path("vulnerabilities");
        if (vulns.isArray()) {
            for (JsonNode v : vulns) {
                CveData cve = parseCve(v.path("cve"));
                if (cve != null) cves.add(cve);
            }
        }
        return new PageResult(total, startIdx, perPage, cves);
    }

    private CveData parseCve(JsonNode cve) {
        String id = cve.path("id").asText(null);
        if (id == null) return null;

        Instant published = parseInstant(cve.path("published").asText(null));
        Instant lastModified = parseInstant(cve.path("lastModified").asText(null));
        if (published == null) published = Instant.now();
        if (lastModified == null) lastModified = published;

        double cvss = 0;
        String cvssVersion = null;
        String severity = null;

        JsonNode metrics = cve.path("metrics");
        String[] keys = {"cvssMetricV31", "cvssMetricV30", "cvssMetricV2"};
        String[] versions = {"3.1", "3.0", "2.0"};
        for (int i = 0; i < keys.length; i++) {
            JsonNode arr = metrics.path(keys[i]);
            if (arr.isArray() && !arr.isEmpty()) {
                JsonNode first = arr.get(0);
                double score = first.path("cvssData").path("baseScore").asDouble(0);
                if (score > 0) {
                    cvss = score;
                    cvssVersion = versions[i];
                    severity = first.path("cvssData").path("baseSeverity").asText(null);
                    if (severity == null) severity = first.path("baseSeverity").asText(null);
                    break;
                }
            }
        }

        String description = "";
        JsonNode descriptions = cve.path("descriptions");
        if (descriptions.isArray()) {
            for (JsonNode d : descriptions) {
                if ("en".equals(d.path("lang").asText())) {
                    description = d.path("value").asText("");
                    break;
                }
            }
            if (description.isEmpty() && !descriptions.isEmpty()) {
                description = descriptions.get(0).path("value").asText("");
            }
        }

        List<CpeMatch> cpeMatches = new ArrayList<>();
        JsonNode configurations = cve.path("configurations");
        if (configurations.isArray()) {
            for (JsonNode config : configurations) {
                collectCpeMatches(config, cpeMatches);
            }
        }

        return new CveData(id, published, lastModified, cvss, cvssVersion, severity, description, cpeMatches);
    }

    private void collectCpeMatches(JsonNode config, List<CpeMatch> out) {
        JsonNode nodes = config.path("nodes");
        if (nodes.isArray()) {
            for (JsonNode node : nodes) {
                JsonNode cpeMatch = node.path("cpeMatch");
                if (cpeMatch.isArray()) {
                    for (JsonNode m : cpeMatch) {
                        if (!m.path("vulnerable").asBoolean(true)) continue;
                        CpeMatch match = parseCpeMatch(m);
                        if (match != null) out.add(match);
                    }
                }
                // recurse into child nodes (AND/OR configurations)
                collectCpeMatches(node, out);
            }
        }
    }

    private CpeMatch parseCpeMatch(JsonNode m) {
        String criteria = m.path("criteria").asText("");
        if (!criteria.startsWith("cpe:2.3:")) return null;
        String[] parts = criteria.split(":", -1);
        if (parts.length < 6) return null;
        // cpe:2.3:part:vendor:product:version:update:edition:...
        String vendor = emptyToNull(parts[3]);
        String product = parts[4];
        String versionInCpe = parts[5];
        String versionExact = (versionInCpe.equals("*") || versionInCpe.equals("-")) ? null : versionInCpe;

        String vsi = m.has("versionStartIncluding") ? m.get("versionStartIncluding").asText(null) : null;
        String vse = m.has("versionStartExcluding") ? m.get("versionStartExcluding").asText(null) : null;
        String vei = m.has("versionEndIncluding") ? m.get("versionEndIncluding").asText(null) : null;
        String vee = m.has("versionEndExcluding") ? m.get("versionEndExcluding").asText(null) : null;

        return new CpeMatch(vendor, product, versionExact, vsi, vse, vei, vee);
    }

    private static Instant parseInstant(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            if (s.endsWith("Z") || s.matches(".*[+-]\\d{2}:?\\d{2}$")) {
                return Instant.parse(s);
            }
            // NVD timestamps are like "2021-12-10T10:15:00.000" — treat as UTC
            return Instant.parse(s + "Z");
        } catch (Exception e) {
            log.debug("Failed to parse instant: {}", s);
            return null;
        }
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isEmpty() || "*".equals(s) || "-".equals(s)) ? null : s;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
