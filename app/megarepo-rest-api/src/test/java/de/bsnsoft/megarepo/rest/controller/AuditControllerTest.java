package de.bsnsoft.megarepo.rest.controller;

import de.bsnsoft.megarepo.database.entity.AuditLogEntity;
import de.bsnsoft.megarepo.repository.AuditService;
import de.bsnsoft.megarepo.rest.advice.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AuditControllerTest {

    private MockMvc mockMvc;

    @Mock
    private AuditService auditService;

    @Mock
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        var controller = new AuditController(auditService, new com.fasterxml.jackson.databind.ObjectMapper());
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void listAll_returnsEntries() throws Exception {
        var entry = createAuditEntry("DOWNLOAD", "maven-releases", "admin", "com/example/lib.jar");
        when(auditService.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(entry)));

        mockMvc.perform(get("/api/v1/audit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].action").value("DOWNLOAD"))
                .andExpect(jsonPath("$.items[0].repository").value("maven-releases"))
                .andExpect(jsonPath("$.items[0].userId").value("admin"))
                .andExpect(jsonPath("$.items[0].path").value("com/example/lib.jar"));
    }

    @Test
    void filterByRepository_callsCorrectService() throws Exception {
        when(auditService.findByRepository(eq("maven-releases"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/v1/audit").param("repository", "maven-releases"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)));

        verify(auditService).findByRepository(eq("maven-releases"), any(Pageable.class));
    }

    @Test
    void filterByUser_callsCorrectService() throws Exception {
        var entry = createAuditEntry("UPLOAD", "npm-hosted", "deployer", "lodash/-/lodash-4.17.21.tgz");
        when(auditService.findByUser(eq("deployer"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(entry)));

        mockMvc.perform(get("/api/v1/audit").param("user", "deployer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].userId").value("deployer"));

        verify(auditService).findByUser(eq("deployer"), any(Pageable.class));
    }

    @Test
    void filterByAction_callsCorrectService() throws Exception {
        when(auditService.findByAction(eq("PROXY_FETCH"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/v1/audit").param("action", "PROXY_FETCH"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)));

        verify(auditService).findByAction(eq("PROXY_FETCH"), any(Pageable.class));
    }

    @Test
    void filterByRepositoryAndAction_callsCorrectService() throws Exception {
        when(auditService.findByRepositoryAndAction(eq("maven-central"), eq("PROXY_FETCH"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/v1/audit")
                        .param("repository", "maven-central")
                        .param("action", "PROXY_FETCH"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)));

        verify(auditService).findByRepositoryAndAction(eq("maven-central"), eq("PROXY_FETCH"), any(Pageable.class));
    }

    @Test
    void filterByTimeRange_callsCorrectService() throws Exception {
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = Instant.parse("2026-01-31T23:59:59Z");
        when(auditService.findByTimeRange(eq(from), eq(to), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/v1/audit")
                        .param("from", "2026-01-01T00:00:00Z")
                        .param("to", "2026-01-31T23:59:59Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)));

        verify(auditService).findByTimeRange(eq(from), eq(to), any(Pageable.class));
    }

    @Test
    void filterByRepositoryAndTimeRange_callsCorrectService() throws Exception {
        Instant from = Instant.parse("2026-03-01T00:00:00Z");
        Instant to = Instant.parse("2026-03-28T23:59:59Z");
        when(auditService.findByRepositoryAndTimeRange(
                        eq("maven-releases"), eq(from), eq(to), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/v1/audit")
                        .param("repository", "maven-releases")
                        .param("from", "2026-03-01T00:00:00Z")
                        .param("to", "2026-03-28T23:59:59Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)));

        verify(auditService)
                .findByRepositoryAndTimeRange(eq("maven-releases"), eq(from), eq(to), any(Pageable.class));
    }

    @Test
    void proxyFetchEntry_includesSourceUrl() throws Exception {
        var entry = createAuditEntry("PROXY_FETCH", "maven-central", "anonymous", "org/example/lib.jar");
        entry.setSourceUrl("https://repo1.maven.org/maven2/org/example/lib.jar");
        entry.setDurationMs(250L);

        when(auditService.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(entry)));

        mockMvc.perform(get("/api/v1/audit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].sourceUrl").value("https://repo1.maven.org/maven2/org/example/lib.jar"))
                .andExpect(jsonPath("$.items[0].durationMs").value(250));
    }

    @Test
    void pagination_returnsContinuationToken() throws Exception {
        var entries = new PageImpl<>(
                List.of(createAuditEntry("DOWNLOAD", "maven-releases", "admin", "lib.jar")),
                org.springframework.data.domain.PageRequest.of(0, 50),
                100);

        when(auditService.findAll(any(Pageable.class))).thenReturn(entries);

        mockMvc.perform(get("/api/v1/audit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.continuationToken").isNotEmpty());
    }

    private static AuditLogEntity createAuditEntry(String action, String repo, String user, String path) {
        var entity = new AuditLogEntity();
        entity.setId(1L);
        entity.setTimestamp(Instant.parse("2026-03-28T12:00:00Z"));
        entity.setUserId(user);
        entity.setAction(action);
        entity.setRepository(repo);
        entity.setPath(path);
        entity.setFormat("maven2");
        entity.setSize(1024L);
        entity.setIpAddress("192.168.1.1");
        return entity;
    }
}
