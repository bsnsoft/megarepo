package de.bsnsoft.megarepo.rest.controller;

import de.bsnsoft.megarepo.repository.firewall.report.AdvisoryStoreState;
import de.bsnsoft.megarepo.repository.firewall.report.ComparisonReportRequest;
import de.bsnsoft.megarepo.repository.firewall.report.ComparisonSummary;
import de.bsnsoft.megarepo.repository.firewall.report.CpePurlComparisonReport;
import de.bsnsoft.megarepo.repository.firewall.report.CpePurlComparisonService;
import de.bsnsoft.megarepo.security.SecurityConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestMapping;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class FirewallComparisonReportControllerTest {

    private MockMvc mockMvc;

    @Mock private CpePurlComparisonService comparisonService;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new FirewallComparisonReportController(comparisonService))
                .build();
    }

    @Test
    @DisplayName("the endpoint lives under the path SecurityConfig restricts to nx-admin")
    void endpointIsCoveredByTheAdminRule() {
        String mapping = FirewallComparisonReportController.class
                .getAnnotation(RequestMapping.class)
                .value()[0];
        String guardedPrefix = SecurityConfig.FIREWALL_ADMIN_PATH_PATTERN.replace("**", "");

        assertThat(mapping)
                .as("moving the endpoint out of %s would silently downgrade it to "
                        + "plain authenticated(), the gap NvdFirewallController has",
                        SecurityConfig.FIREWALL_ADMIN_PATH_PATTERN)
                .startsWith(guardedPrefix);
        assertThat(SecurityConfig.FIREWALL_ADMIN_ROLE).isEqualTo("nx-admin");
    }

    @Test
    @DisplayName("JSON carries the summary and the parameters it ran with")
    void jsonExposesTheReport() throws Exception {
        when(comparisonService.run(any())).thenReturn(report());

        mockMvc.perform(get("/api/v1/admin/firewall/cpe-purl-comparison"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.componentsScanned").value(11))
                .andExpect(jsonPath("$.summary.findingsCpeOnly").value(4))
                .andExpect(jsonPath("$.synthetic").value(false))
                .andExpect(jsonPath("$.request.maxComponents").value(
                        ComparisonReportRequest.DEFAULT_MAX_COMPONENTS));
    }

    @Test
    @DisplayName("Markdown comes back as a downloadable file")
    void markdownIsAnAttachment() throws Exception {
        when(comparisonService.run(any())).thenReturn(report());

        mockMvc.perform(get("/api/v1/admin/firewall/cpe-purl-comparison/markdown"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/markdown"))
                .andExpect(header().string(
                        "Content-Disposition",
                        org.hamcrest.Matchers.startsWith(
                                "attachment; filename=\"megarepo-cpe-purl-comparison-")))
                .andExpect(content().string(
                        org.hamcrest.Matchers.containsString(
                                "# Repository Firewall — CPE matching vs. purl matching")));
    }

    @Test
    @DisplayName("query parameters reach the request, and nonsense is clamped rather than rejected")
    void parametersAreForwardedAndClamped() throws Exception {
        when(comparisonService.run(any())).thenReturn(report());
        UUID repositoryId = UUID.randomUUID();

        mockMvc.perform(get("/api/v1/admin/firewall/cpe-purl-comparison")
                        .param("repositoryId", repositoryId.toString())
                        .param("pageSize", "-1")
                        .param("maxSamplesPerKind", "99999")
                        .param("includeAgreementSamples", "true"))
                .andExpect(status().isOk());

        ArgumentCaptor<ComparisonReportRequest> captured =
                ArgumentCaptor.forClass(ComparisonReportRequest.class);
        org.mockito.Mockito.verify(comparisonService).run(captured.capture());

        ComparisonReportRequest request = captured.getValue();
        assertThat(request.repositoryIds()).containsExactly(repositoryId);
        assertThat(request.pageSize()).isEqualTo(ComparisonReportRequest.DEFAULT_PAGE_SIZE);
        assertThat(request.maxSamplesPerKind())
                .isEqualTo(ComparisonReportRequest.MAX_SAMPLES_PER_KIND_LIMIT);
        assertThat(request.includeAgreementSamples()).isTrue();
    }

    private static CpePurlComparisonReport report() {
        ComparisonSummary summary = new ComparisonSummary(
                11, 1, 1, 2, 7, 4, 2, 3, 2, 4, 2, 2, 1, 2,
                new TreeMap<>(java.util.Map.of("raw", 1L)),
                new AdvisoryStoreState(6, 7, 8, 12, 6, 6));
        return new CpePurlComparisonReport(
                Instant.parse("2026-08-05T10:00:00Z"),
                Duration.ofMillis(120),
                ComparisonReportRequest.defaults(),
                false,
                false,
                summary,
                List.of(),
                List.of());
    }
}
