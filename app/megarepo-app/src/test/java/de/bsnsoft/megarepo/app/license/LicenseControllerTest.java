package de.bsnsoft.megarepo.app.license;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class LicenseControllerTest {

    private MockMvc mockMvc;

    @Mock
    private LicenseService licenseService;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new LicenseController(licenseService)).build();
    }

    @Test
    void getStatus_licensed() throws Exception {
        var info = new LicenseInfo("ACME Corp", "admin@example.com", "2026-04-01", "2027-04-01", "lic-test-0001", true);
        when(licenseService.isLicensed()).thenReturn(true);
        when(licenseService.getLicenseInfo()).thenReturn(Optional.of(info));
        when(licenseService.getActiveUserCount()).thenReturn(25);

        mockMvc.perform(get("/api/v1/system/license").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.licensed").value(true))
                .andExpect(jsonPath("$.company").value("ACME Corp"))
                .andExpect(jsonPath("$.email").value("admin@example.com"))
                .andExpect(jsonPath("$.activeUsers").value(25))
                .andExpect(jsonPath("$.requiresPurchase").value(false))
                .andExpect(jsonPath("$.message").value("Licensed to ACME Corp"));
    }

    @Test
    void getStatus_communityEdition_underLimit() throws Exception {
        when(licenseService.isLicensed()).thenReturn(false);
        when(licenseService.getLicenseInfo()).thenReturn(Optional.empty());
        when(licenseService.getActiveUserCount()).thenReturn(10);

        mockMvc.perform(get("/api/v1/system/license").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.licensed").value(false))
                .andExpect(jsonPath("$.company").isEmpty())
                .andExpect(jsonPath("$.activeUsers").value(10))
                .andExpect(jsonPath("$.requiresPurchase").value(false))
                .andExpect(jsonPath("$.message").value("MegaRepo Community Edition"));
    }

    @Test
    void getStatus_communityEdition_overLimit() throws Exception {
        when(licenseService.isLicensed()).thenReturn(false);
        when(licenseService.getLicenseInfo()).thenReturn(Optional.empty());
        when(licenseService.getActiveUserCount()).thenReturn(53);

        mockMvc.perform(get("/api/v1/system/license").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.licensed").value(false))
                .andExpect(jsonPath("$.activeUsers").value(53))
                .andExpect(jsonPath("$.requiresPurchase").value(true))
                .andExpect(jsonPath("$.message").value(
                        "MegaRepo Community Edition \u2014 53 active users detected (limit: 50). "
                                + "Purchase a business license at bsnsoft.de/megarepo"));
    }

    @Test
    void uploadLicense_valid() throws Exception {
        var info = new LicenseInfo("ACME Corp", "admin@example.com", "2026-04-01", "2027-04-01", "lic-test-0001", true);
        when(licenseService.uploadLicense(any(byte[].class))).thenReturn(info);
        when(licenseService.getActiveUserCount()).thenReturn(25);

        var licenseJson = """
                {
                  "company": "ACME Corp",
                  "email": "admin@example.com",
                  "issuedAt": "2026-04-01",
                  "licenseId": "lic-test-0001",
                  "signature": "base64sig"
                }
                """;

        mockMvc.perform(post("/api/v1/system/license")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(licenseJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.licensed").value(true))
                .andExpect(jsonPath("$.company").value("ACME Corp"));
    }

    @Test
    void deleteLicense() throws Exception {
        mockMvc.perform(delete("/api/v1/system/license")).andExpect(status().isNoContent());

        verify(licenseService).removeLicense();
    }
}
