package de.bsnsoft.megarepo.rest.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.bsnsoft.megarepo.core.exception.NotFoundException;
import de.bsnsoft.megarepo.database.entity.SslCertificateEntity;
import de.bsnsoft.megarepo.rest.advice.GlobalExceptionHandler;
import de.bsnsoft.megarepo.rest.dto.ssl.AddCertificateFromPemRequest;
import de.bsnsoft.megarepo.security.ssl.SslCertificateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class SslCertificateControllerTest {

    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private SslCertificateService sslCertificateService;

    @BeforeEach
    void setUp() {
        var controller = new SslCertificateController(sslCertificateService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void listCertificates_returnsAll() throws Exception {
        var cert1 = createTestEntity("CN=test1.example.com", "Example Corp");
        var cert2 = createTestEntity("CN=test2.example.com", "Another Corp");

        when(sslCertificateService.listCertificates()).thenReturn(List.of(cert1, cert2));

        mockMvc.perform(get("/api/v1/security/ssl/truststore"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].subjectCn").value("test1.example.com"))
                .andExpect(jsonPath("$[0].issuerOrg").value("Example Corp"))
                .andExpect(jsonPath("$[1].subjectCn").value("test2.example.com"));
    }

    @Test
    void addFromPem_returns201() throws Exception {
        var saved = createTestEntity("CN=new.example.com", "New Corp");
        when(sslCertificateService.addCertificateFromPem(any())).thenReturn(saved);

        var request = new AddCertificateFromPemRequest("-----BEGIN CERTIFICATE-----\ntest\n-----END CERTIFICATE-----");

        mockMvc.perform(post("/api/v1/security/ssl/truststore")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.subjectCn").value("new.example.com"))
                .andExpect(jsonPath("$.fingerprint").value("AA:BB:CC:DD"));

        verify(sslCertificateService).addCertificateFromPem(any());
    }

    @Test
    void addFromPem_invalidPem_returns400() throws Exception {
        when(sslCertificateService.addCertificateFromPem(any()))
                .thenThrow(new IllegalArgumentException("Invalid PEM certificate"));

        var request = new AddCertificateFromPemRequest("not-a-real-cert");

        mockMvc.perform(post("/api/v1/security/ssl/truststore")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid PEM certificate"));
    }

    @Test
    void fetchFromHost_returnsCerts() throws Exception {
        var cert = createTestEntity("CN=google.com", "Remote Corp");
        when(sslCertificateService.fetchCertificatesFromHost("google.com", 443))
                .thenReturn(List.of(cert));

        mockMvc.perform(get("/api/v1/security/ssl?host=google.com&port=443"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].subjectCn").value("google.com"));
    }

    @Test
    void fetchFromHost_defaultPort443() throws Exception {
        when(sslCertificateService.fetchCertificatesFromHost("google.com", 443))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/v1/security/ssl?host=google.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void deleteCertificate_returns204() throws Exception {
        UUID id = UUID.randomUUID();
        doNothing().when(sslCertificateService).deleteCertificate(id);

        mockMvc.perform(delete("/api/v1/security/ssl/truststore/" + id))
                .andExpect(status().isNoContent());

        verify(sslCertificateService).deleteCertificate(id);
    }

    @Test
    void deleteCertificate_notFound_returns404() throws Exception {
        UUID id = UUID.randomUUID();
        doThrow(new NotFoundException("SSL certificate not found: " + id))
                .when(sslCertificateService)
                .deleteCertificate(id);

        mockMvc.perform(delete("/api/v1/security/ssl/truststore/" + id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    private static SslCertificateEntity createTestEntity(String subjectDn, String issuerOrg) {
        SslCertificateEntity entity = new SslCertificateEntity();
        entity.setId(UUID.randomUUID());
        entity.setPem("-----BEGIN CERTIFICATE-----\ntest\n-----END CERTIFICATE-----");
        entity.setSubjectCn(subjectDn.replace("CN=", ""));
        entity.setIssuerCn("Test CA");
        entity.setIssuerOrg(issuerOrg);
        entity.setFingerprint("AA:BB:CC:DD");
        entity.setIssuedOn(Instant.parse("2024-01-01T00:00:00Z"));
        entity.setExpiresOn(Instant.parse("2025-01-01T00:00:00Z"));
        entity.setCreatedAt(Instant.parse("2024-06-01T00:00:00Z"));
        return entity;
    }
}
