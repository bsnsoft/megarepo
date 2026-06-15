package de.bsnsoft.megarepo.rest.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.bsnsoft.megarepo.database.entity.OutboundProxySettingsEntity;
import de.bsnsoft.megarepo.repository.proxy.OutboundProxySettingsService;
import de.bsnsoft.megarepo.rest.advice.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class HttpProxyControllerTest {

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private OutboundProxySettingsService service;

    @BeforeEach
    void setUp() {
        var controller = new HttpProxyController(service);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static OutboundProxySettingsEntity entity(boolean configured, String password) {
        var e = new OutboundProxySettingsEntity();
        e.setConfigured(configured);
        e.setEnabled(true);
        e.setHost("proxy.example.com");
        e.setPort(3128);
        e.setUsername("user");
        e.setPassword(password);
        e.setNonProxyHosts("localhost");
        return e;
    }

    @Test
    void get_neverExposesPassword_andReportsSource() throws Exception {
        when(service.load()).thenReturn(entity(true, "super-secret"));

        mockMvc.perform(get("/api/v1/system/http-proxy"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.host").value("proxy.example.com"))
                .andExpect(jsonPath("$.port").value(3128))
                .andExpect(jsonPath("$.username").value("user"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordSet").value(true))
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.source").value("database"));
    }

    @Test
    void get_reportsEnvironmentSource_whenNotConfigured() throws Exception {
        when(service.load()).thenReturn(entity(false, null));

        mockMvc.perform(get("/api/v1/system/http-proxy"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passwordSet").value(false))
                .andExpect(jsonPath("$.configured").value(false))
                .andExpect(jsonPath("$.source").value("environment"));
    }

    @Test
    void update_passesFieldsToService_andMasksPasswordInResponse() throws Exception {
        when(service.save(eq(true), eq("proxy.example.com"), eq(3128), eq("user"),
                eq("new-secret"), eq("localhost")))
                .thenReturn(entity(true, "new-secret"));

        String body = objectMapper.writeValueAsString(new java.util.HashMap<>() {{
            put("enabled", true);
            put("host", "proxy.example.com");
            put("port", 3128);
            put("username", "user");
            put("password", "new-secret");
            put("nonProxyHosts", "localhost");
        }});

        mockMvc.perform(put("/api/v1/system/http-proxy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordSet").value(true));

        verify(service).save(true, "proxy.example.com", 3128, "user", "new-secret", "localhost");
    }

    @Test
    void update_withBlankPassword_delegatesNullToService() throws Exception {
        when(service.save(eq(true), eq("proxy.example.com"), eq(3128), isNull(),
                isNull(), isNull()))
                .thenReturn(entity(true, "kept"));

        String body = objectMapper.writeValueAsString(new java.util.HashMap<>() {{
            put("enabled", true);
            put("host", "proxy.example.com");
            put("port", 3128);
        }});

        mockMvc.perform(put("/api/v1/system/http-proxy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        // Absent password in the request -> controller forwards null; service keeps stored.
        verify(service).save(eq(true), eq("proxy.example.com"), eq(3128), isNull(), isNull(), isNull());
    }

    @Test
    void update_rejectsInvalidPort() throws Exception {
        String body = objectMapper.writeValueAsString(new java.util.HashMap<>() {{
            put("enabled", true);
            put("host", "proxy.example.com");
            put("port", 70000);
        }});

        mockMvc.perform(put("/api/v1/system/http-proxy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        verify(service, org.mockito.Mockito.never()).save(
                org.mockito.ArgumentMatchers.anyBoolean(), any(), org.mockito.ArgumentMatchers.anyInt(),
                any(), any(), any());
    }
}
