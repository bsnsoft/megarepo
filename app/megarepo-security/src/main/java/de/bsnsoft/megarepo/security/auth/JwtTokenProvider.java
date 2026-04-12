package de.bsnsoft.megarepo.security.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Set;

@Component
public class JwtTokenProvider {

    private final SecretKey signingKey;
    private final Duration accessTokenExpiry;
    private final Duration refreshTokenExpiry;

    public JwtTokenProvider(
            @Value("${megarepo.security.jwt.secret}") String secret,
            @Value("${megarepo.security.jwt.access-token-expiry}") Duration accessTokenExpiry,
            @Value("${megarepo.security.jwt.refresh-token-expiry}") Duration refreshTokenExpiry) {
        this.signingKey = Keys.hmacShaKeyFor(padSecret(secret));
        this.accessTokenExpiry = accessTokenExpiry;
        this.refreshTokenExpiry = refreshTokenExpiry;
    }

    public String generateAccessToken(String userId, Set<String> roles) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId)
                .claim("roles", String.join(",", roles))
                .claim("type", "access")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTokenExpiry)))
                .signWith(signingKey)
                .compact();
    }

    public String generateRefreshToken(String userId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId)
                .claim("type", "refresh")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(refreshTokenExpiry)))
                .signWith(signingKey)
                .compact();
    }

    public boolean validateToken(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public String getUserIdFromToken(String token) {
        return parseClaims(token).getSubject();
    }

    public Set<String> getRolesFromToken(String token) {
        Claims claims = parseClaims(token);
        String roles = claims.get("roles", String.class);
        if (roles == null || roles.isBlank()) {
            return Set.of();
        }
        return Set.of(roles.split(","));
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private static byte[] padSecret(String secret) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length >= 32) {
            return bytes;
        }
        byte[] padded = new byte[32];
        System.arraycopy(bytes, 0, padded, 0, bytes.length);
        return padded;
    }
}
