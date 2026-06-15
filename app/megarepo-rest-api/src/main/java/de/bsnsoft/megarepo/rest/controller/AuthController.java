package de.bsnsoft.megarepo.rest.controller;

import de.bsnsoft.megarepo.rest.dto.security.LoginRequest;
import de.bsnsoft.megarepo.rest.dto.security.TokenResponse;
import de.bsnsoft.megarepo.security.auth.JwtTokenProvider;
import de.bsnsoft.megarepo.security.auth.ratelimit.LoginRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/security/auth")
public class AuthController {

    private final AuthenticationProvider authenticationProvider;
    private final JwtTokenProvider jwtTokenProvider;
    private final LoginRateLimiter rateLimiter;

    public AuthController(
            AuthenticationProvider authenticationProvider,
            JwtTokenProvider jwtTokenProvider,
            LoginRateLimiter rateLimiter) {
        this.authenticationProvider = authenticationProvider;
        this.jwtTokenProvider = jwtTokenProvider;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(
            @Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        String clientIp = resolveClientIp(httpRequest);

        try {
            Authentication authentication = authenticationProvider.authenticate(
                    new UsernamePasswordAuthenticationToken(request.username(), request.password()));

            // Successful login — clear any tracked failures for this IP
            rateLimiter.clearFailures(clientIp);

            Set<String> roles = authentication.getAuthorities().stream()
                    .map(auth -> auth.getAuthority().replace("ROLE_", ""))
                    .collect(Collectors.toSet());

            String token = jwtTokenProvider.generateAccessToken(authentication.getName(), roles);
            return ResponseEntity.ok(new TokenResponse(token));
        } catch (BadCredentialsException e) {
            rateLimiter.recordFailure(clientIp);
            throw e;
        }
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(@RequestHeader("Authorization") String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new BadCredentialsException("Missing or invalid Authorization header");
        }

        String token = authHeader.substring("Bearer ".length());
        if (!jwtTokenProvider.validateToken(token)) {
            throw new BadCredentialsException("Invalid or expired token");
        }

        String userId = jwtTokenProvider.getUserIdFromToken(token);
        Set<String> roles = jwtTokenProvider.getRolesFromToken(token);

        String newToken = jwtTokenProvider.generateAccessToken(userId, roles);
        return ResponseEntity.ok(new TokenResponse(newToken));
    }

    /**
     * Regenerate the caller's API key — the same MegaRepo JWT used as the NuGet
     * push API key ({@code dotnet nuget push --api-key …}) and as the npm/Maven
     * bearer token. Issues a fresh token for the currently authenticated user and
     * returns it so the UI can display and copy it.
     *
     * <p>This is reachable as an authenticated request (it relies on the security
     * context, not a token in a header), which lets the UI offer an explicit
     * "Reset API key" action distinct from the silent sliding-session
     * {@code /refresh}.
     *
     * <p>Note: tokens are stateless JWTs, so the previous token remains valid until
     * its natural expiry — there is no server-side revocation list. See the admin
     * guide for the security implications and the planned persistent
     * personal-access-token model.
     */
    @PostMapping("/regenerate-token")
    public ResponseEntity<TokenResponse> regenerateToken() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new BadCredentialsException("Not authenticated");
        }

        String userId = authentication.getName();
        Set<String> roles = authentication.getAuthorities().stream()
                .map(auth -> auth.getAuthority().replace("ROLE_", ""))
                .collect(Collectors.toSet());

        String newToken = jwtTokenProvider.generateAccessToken(userId, roles);
        return ResponseEntity.ok(new TokenResponse(newToken));
    }
}
