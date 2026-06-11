package de.bsnsoft.megarepo.repository.proxy;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutboundProxyPropertiesTest {

    private static OutboundProxyProperties bind(Map<String, String> properties) {
        var source = new MapConfigurationPropertySource(properties);
        return new Binder(source)
                .bind("megarepo.outbound-proxy", OutboundProxyProperties.class)
                .get();
    }

    @Nested
    class Binding {

        @Test
        void bindsAllProperties() {
            var props = bind(Map.of(
                    "megarepo.outbound-proxy.enabled", "true",
                    "megarepo.outbound-proxy.host", "proxy.corp.example.com",
                    "megarepo.outbound-proxy.port", "8080",
                    "megarepo.outbound-proxy.username", "megarepo",
                    "megarepo.outbound-proxy.password", "secret",
                    "megarepo.outbound-proxy.non-proxy-hosts", "localhost,*.internal.example.com"));

            assertTrue(props.enabled());
            assertEquals("proxy.corp.example.com", props.host());
            assertEquals(8080, props.port());
            assertEquals("megarepo", props.username());
            assertEquals("secret", props.password());
            assertEquals(List.of("localhost", "*.internal.example.com"), props.nonProxyHosts());
            assertTrue(props.hasAuth());
        }

        @Test
        void defaultsApplyWhenOnlyEnabledIsSet() {
            var props = bind(Map.of(
                    "megarepo.outbound-proxy.enabled", "true",
                    "megarepo.outbound-proxy.host", "proxy.local"));

            assertTrue(props.enabled());
            assertEquals(3128, props.port());
            assertNull(props.username());
            assertNull(props.password());
            assertEquals(List.of(), props.nonProxyHosts());
            assertFalse(props.hasAuth());
        }

        @Test
        void bindsFromEnvironmentVariableNames() {
            // Verifies the relaxed binding used for values.yaml / container env:
            // MEGAREPO_OUTBOUNDPROXY_* maps onto megarepo.outbound-proxy.*
            // The property source must be named "systemEnvironment" for Spring Boot
            // to apply the SYSTEM_ENVIRONMENT name mapping (UPPER_SNAKE -> kebab).
            var envSource = new SystemEnvironmentPropertySource(
                    "systemEnvironment",
                    Map.of(
                            "MEGAREPO_OUTBOUNDPROXY_ENABLED", "true",
                            "MEGAREPO_OUTBOUNDPROXY_HOST", "proxy.corp.example.com",
                            "MEGAREPO_OUTBOUNDPROXY_PORT", "3129",
                            "MEGAREPO_OUTBOUNDPROXY_USERNAME", "svc-megarepo",
                            "MEGAREPO_OUTBOUNDPROXY_PASSWORD", "s3cret",
                            "MEGAREPO_OUTBOUNDPROXY_NONPROXYHOSTS", "localhost,*.corp.example.com"));

            var props = new Binder(ConfigurationPropertySources.from(envSource))
                    .bind("megarepo.outbound-proxy", OutboundProxyProperties.class)
                    .get();

            assertTrue(props.enabled());
            assertEquals("proxy.corp.example.com", props.host());
            assertEquals(3129, props.port());
            assertEquals("svc-megarepo", props.username());
            assertEquals("s3cret", props.password());
            assertEquals(List.of("localhost", "*.corp.example.com"), props.nonProxyHosts());
        }

        @Test
        void hasAuthRequiresBothUsernameAndPassword() {
            assertFalse(new OutboundProxyProperties(true, "p", 3128, "user", null, List.of()).hasAuth());
            assertFalse(new OutboundProxyProperties(true, "p", 3128, null, "pw", List.of()).hasAuth());
            assertFalse(new OutboundProxyProperties(true, "p", 3128, "", "", List.of()).hasAuth());
            assertTrue(new OutboundProxyProperties(true, "p", 3128, "user", "pw", List.of()).hasAuth());
        }
    }

    @Nested
    class NonProxyHostMatching {

        private final OutboundProxyProperties props = new OutboundProxyProperties(
                true, "proxy.local", 3128, null, null,
                List.of("localhost", "127.0.0.1", "*.internal.example.com", "10.0.*"));

        @Test
        void matchesExactHost() {
            assertTrue(props.isNonProxyHost("localhost"));
            assertTrue(props.isNonProxyHost("127.0.0.1"));
        }

        @Test
        void matchesWildcardSuffixPattern() {
            assertTrue(props.isNonProxyHost("repo.internal.example.com"));
            assertTrue(props.isNonProxyHost("a.b.internal.example.com"));
            assertFalse(props.isNonProxyHost("internal.example.com"));
            assertFalse(props.isNonProxyHost("evil.example.com"));
        }

        @Test
        void matchesWildcardPrefixPattern() {
            assertTrue(props.isNonProxyHost("10.0.1.2"));
            assertFalse(props.isNonProxyHost("10.1.0.2"));
        }

        @Test
        void matchingIsCaseInsensitive() {
            assertTrue(props.isNonProxyHost("LOCALHOST"));
            assertTrue(props.isNonProxyHost("Repo.Internal.Example.Com"));
        }

        @Test
        void doesNotMatchUnrelatedHosts() {
            assertFalse(props.isNonProxyHost("repo1.maven.org"));
            assertFalse(props.isNonProxyHost(null));
        }

        @Test
        void emptyListMatchesNothing() {
            var noBypass = new OutboundProxyProperties(true, "proxy.local", 3128, null, null, List.of());
            assertFalse(noBypass.isNonProxyHost("localhost"));
        }
    }
}
