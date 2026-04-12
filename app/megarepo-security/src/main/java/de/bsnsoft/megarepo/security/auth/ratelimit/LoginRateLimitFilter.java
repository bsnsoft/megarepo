package de.bsnsoft.megarepo.security.auth.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;

/**
 * Servlet filter that rejects login requests from IPs that have been rate-limited
 * due to excessive failed authentication attempts.
 */
@Component
public class LoginRateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(LoginRateLimitFilter.class);

    private static final String LOGIN_PATH = "/api/v1/security/auth/login";

    private final LoginRateLimiter rateLimiter;

    public LoginRateLimitFilter(LoginRateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        if (!isLoginRequest(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        String clientIp = resolveClientIp(request);

        if (rateLimiter.isBlocked(clientIp)) {
            log.warn("Rate-limited login attempt from IP {}", clientIp);
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(
                    "{\"status\":429,\"error\":\"Too Many Requests\","
                            + "\"message\":\"Too many failed login attempts. Please try again later.\","
                            + "\"timestamp\":\"" + Instant.now() + "\"}");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean isLoginRequest(HttpServletRequest request) {
        return "POST".equalsIgnoreCase(request.getMethod()) && LOGIN_PATH.equals(request.getRequestURI());
    }

    /**
     * Resolve the client IP, respecting X-Forwarded-For when behind a reverse proxy.
     */
    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            // Take the first IP in the chain (original client)
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
