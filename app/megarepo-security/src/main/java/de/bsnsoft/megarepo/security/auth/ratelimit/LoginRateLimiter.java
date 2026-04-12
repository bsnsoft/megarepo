package de.bsnsoft.megarepo.security.auth.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory rate limiter for failed login attempts. Tracks failures per IP address
 * and blocks IPs that exceed the threshold within the configured window.
 *
 * <p>Suitable for single-node deployments. For clustered deployments, replace with
 * a Redis-backed implementation.</p>
 */
@Component
public class LoginRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(LoginRateLimiter.class);

    private static final int MAX_ATTEMPTS = 10;
    private static final long WINDOW_SECONDS = 15 * 60; // 15 minutes

    private final ConcurrentHashMap<String, FailureRecord> attempts = new ConcurrentHashMap<>();

    /**
     * Check whether the given IP is currently blocked.
     *
     * @param ip the client IP address
     * @return true if the IP is blocked due to too many failed attempts
     */
    public boolean isBlocked(String ip) {
        FailureRecord record = attempts.get(ip);
        if (record == null) {
            return false;
        }
        // If the window has expired, clean up and allow
        if (record.windowStart.plusSeconds(WINDOW_SECONDS).isBefore(Instant.now())) {
            attempts.remove(ip);
            return false;
        }
        return record.count >= MAX_ATTEMPTS;
    }

    /**
     * Record a failed login attempt for the given IP.
     *
     * @param ip the client IP address
     */
    public void recordFailure(String ip) {
        Instant now = Instant.now();
        attempts.compute(ip, (key, existing) -> {
            if (existing == null || existing.windowStart.plusSeconds(WINDOW_SECONDS).isBefore(now)) {
                // Start a new window
                return new FailureRecord(1, now);
            }
            int newCount = existing.count + 1;
            if (newCount == MAX_ATTEMPTS) {
                log.warn("IP {} blocked after {} failed login attempts within {} minutes", ip, newCount, WINDOW_SECONDS / 60);
            }
            return new FailureRecord(newCount, existing.windowStart);
        });
    }

    /**
     * Clear the failure record for the given IP (e.g., after a successful login).
     *
     * @param ip the client IP address
     */
    public void clearFailures(String ip) {
        attempts.remove(ip);
    }

    /**
     * Evict expired entries. Called periodically to prevent memory leaks.
     */
    public void evictExpired() {
        Instant now = Instant.now();
        attempts.entrySet().removeIf(entry ->
                entry.getValue().windowStart.plusSeconds(WINDOW_SECONDS).isBefore(now));
    }

    private record FailureRecord(int count, Instant windowStart) {}
}
