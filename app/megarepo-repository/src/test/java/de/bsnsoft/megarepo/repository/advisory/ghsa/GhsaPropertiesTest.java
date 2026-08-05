package de.bsnsoft.megarepo.repository.advisory.ghsa;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Binding of {@code megarepo.firewall.ghsa.*}, following the OutboundProxyProperties pattern. */
class GhsaPropertiesTest {

    private static GhsaProperties bind(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties))
                .bind("megarepo.firewall.ghsa", GhsaProperties.class)
                .orElseGet(GhsaProperties::defaults);
    }

    @Test
    void anUnconfiguredDeploymentGetsATokenLessDefault() {
        GhsaProperties properties = bind(Map.of());

        assertTrue(properties.enabled());
        assertFalse(properties.hasToken());
        assertNull(properties.token());
        assertEquals(GhsaProperties.DEFAULT_BASE_URL, properties.baseUrl());
        assertEquals(100, properties.pageSize());
        assertEquals(5, properties.pagesPerSync());
        assertEquals("reviewed", properties.type());
        assertEquals(Duration.ofSeconds(30), properties.requestTimeout());
    }

    @Test
    void bindsEveryProperty() {
        GhsaProperties properties = bind(Map.of(
                "megarepo.firewall.ghsa.enabled", "false",
                "megarepo.firewall.ghsa.token", "ghp_0123456789abcdefghijklmnopqrstuvwxyz",
                "megarepo.firewall.ghsa.base-url", "https://ghe.example.com/api/v3/advisories",
                "megarepo.firewall.ghsa.page-size", "50",
                "megarepo.firewall.ghsa.pages-per-sync", "20",
                "megarepo.firewall.ghsa.type", "malware",
                "megarepo.firewall.ghsa.request-timeout", "45s",
                "megarepo.firewall.ghsa.rate-limit-reserve", "100"));

        assertFalse(properties.enabled());
        assertTrue(properties.hasToken());
        assertEquals("https://ghe.example.com/api/v3/advisories", properties.baseUrl());
        assertEquals(50, properties.pageSize());
        assertEquals(20, properties.pagesPerSync());
        assertEquals("malware", properties.type());
        assertEquals(Duration.ofSeconds(45), properties.requestTimeout());
        assertEquals(100, properties.rateLimitReserve());
    }

    @Test
    void anEmptyTokenIsNoToken() {
        // application.yml binds ${MEGAREPO_GHSA_TOKEN:} — unset means an empty string.
        assertFalse(bind(Map.of("megarepo.firewall.ghsa.token", "")).hasToken());
        assertFalse(bind(Map.of("megarepo.firewall.ghsa.token", "   ")).hasToken());
    }

    @Test
    void nonsensicalValuesAreClampedRatherThanRejected() {
        GhsaProperties properties = bind(Map.of(
                "megarepo.firewall.ghsa.page-size", "100000",
                "megarepo.firewall.ghsa.pages-per-sync", "0",
                "megarepo.firewall.ghsa.rate-limit-reserve", "-5"));

        assertEquals(GhsaProperties.MAX_PAGE_SIZE, properties.pageSize());
        assertEquals(1, properties.pagesPerSync());
        assertEquals(0, properties.rateLimitReserve());
    }

    @Test
    void theTokenNeverAppearsInToString() {
        // Spring logs bound properties when binding fails, and the record's generated
        // toString would print the credential.
        GhsaProperties properties =
                bind(Map.of("megarepo.firewall.ghsa.token", "ghp_0123456789abcdefghijklmnopqrstuvwxyz"));

        assertFalse(properties.toString().contains("ghp_0123456789abcdefghijklmnopqrstuvwxyz"));
        assertTrue(properties.toString().contains("<set>"));
        assertTrue(bind(Map.of()).toString().contains("<unset>"));
    }
}
