package de.bsnsoft.megarepo.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "megarepo")
public record MegaRepoProperties(
        String dataDirectory,
        BlobStoreProperties blobStores,
        SecurityProperties security,
        ProxyProperties proxy
) {

    public record BlobStoreProperties(String defaultPath) {
    }

    public record SecurityProperties(JwtProperties jwt, String defaultAdminPassword) {
    }

    public record JwtProperties(String secret, Duration accessTokenExpiry, Duration refreshTokenExpiry) {
    }

    public record ProxyProperties(String userAgent, Duration connectTimeout, Duration readTimeout) {
    }
}
