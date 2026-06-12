package de.bsnsoft.megarepo.security.auth;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.InsufficientAuthenticationException;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiAuthenticationEntryPointTest {

    private final UiAuthenticationEntryPoint entryPoint = new UiAuthenticationEntryPoint();

    @Test
    void commence_returns401WithoutWwwAuthenticateHeader() throws IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/repositories");
        MockHttpServletResponse response = new MockHttpServletResponse();

        entryPoint.commence(request, response, new InsufficientAuthenticationException("not authenticated"));

        assertEquals(401, response.getStatus());
        // No WWW-Authenticate header — the browser must NOT show its native
        // Basic-Auth popup for SPA requests.
        assertNull(response.getHeader("WWW-Authenticate"));
    }

    @Test
    void commence_writesJsonErrorBody() throws IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/repositories");
        MockHttpServletResponse response = new MockHttpServletResponse();

        entryPoint.commence(request, response, new InsufficientAuthenticationException("not authenticated"));

        assertTrue(response.getContentType().startsWith(MediaType.APPLICATION_JSON_VALUE));
        String body = response.getContentAsString();
        assertTrue(body.contains("\"status\":401"));
        assertTrue(body.contains("\"error\":\"Unauthorized\""));
        assertTrue(body.contains("\"message\""));
        assertTrue(body.contains("\"timestamp\""));
    }
}
