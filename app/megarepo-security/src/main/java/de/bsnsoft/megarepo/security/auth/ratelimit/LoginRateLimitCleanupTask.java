package de.bsnsoft.megarepo.security.auth.ratelimit;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically evicts expired rate-limit entries to prevent unbounded memory growth.
 */
@Component
public class LoginRateLimitCleanupTask {

    private final LoginRateLimiter rateLimiter;

    public LoginRateLimitCleanupTask(LoginRateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Scheduled(fixedRate = 5 * 60 * 1000) // every 5 minutes
    public void cleanup() {
        rateLimiter.evictExpired();
    }
}
