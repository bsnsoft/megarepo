package de.bsnsoft.megarepo.security.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;
import java.time.Instant;

/**
 * Authentication entry point for browser/SPA requests (web UI).
 *
 * <p>Returns a plain {@code 401} with a JSON error body and deliberately
 * <b>no</b> {@code WWW-Authenticate} header. A {@code WWW-Authenticate: Basic}
 * challenge would make browsers pop up their native username/password dialog
 * on every expired session — instead the SPA detects the 401 and redirects to
 * its own login screen.
 *
 * <p>Tooling clients (Maven, npm, pip, Docker) talk to the repository
 * endpoints, which keep the standard Basic challenge — see the
 * {@code DelegatingAuthenticationEntryPoint} wiring in {@code SecurityConfig}.
 */
public class UiAuthenticationEntryPoint implements AuthenticationEntryPoint {

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException)
            throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(
                "{\"status\":401,\"error\":\"Unauthorized\","
                        + "\"message\":\"Authentication required. Please log in.\","
                        + "\"timestamp\":\"" + Instant.now() + "\"}");
    }
}
