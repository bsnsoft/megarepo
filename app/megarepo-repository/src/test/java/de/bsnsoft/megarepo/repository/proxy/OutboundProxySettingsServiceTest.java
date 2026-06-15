package de.bsnsoft.megarepo.repository.proxy;

import de.bsnsoft.megarepo.database.entity.OutboundProxySettingsEntity;
import de.bsnsoft.megarepo.database.repository.OutboundProxySettingsJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OutboundProxySettingsService}: the layering between the
 * deployment-side fallback ({@code megarepo.outbound-proxy.*}) and the UI-managed
 * runtime configuration, the write-only password handling, and the propagation of
 * effective configuration into {@link RemoteHttpClient}.
 */
class OutboundProxySettingsServiceTest {

    private static final Integer ID = 1;

    private OutboundProxySettingsJpaRepository repository;
    private RemoteHttpClient remoteHttpClient;
    private OutboundProxyProperties fallback;
    private OutboundProxySettingsService service;

    @BeforeEach
    void setUp() {
        repository = mock(OutboundProxySettingsJpaRepository.class);
        remoteHttpClient = mock(RemoteHttpClient.class);
        fallback = new OutboundProxyProperties(
                true, "env-proxy.example.com", 8080, "envuser", "envpass", List.of("localhost"));
        service = new OutboundProxySettingsService(repository, fallback, remoteHttpClient);
        // Persist returns the argument, as a real save would.
        when(repository.save(any(OutboundProxySettingsEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void effectiveConfig_usesEnvironmentFallback_whenNotConfigured() {
        when(repository.findById(ID)).thenReturn(Optional.empty());

        OutboundProxyProperties effective = service.effectiveConfig();

        assertSame(fallback, effective, "an absent/unconfigured row must yield the env fallback");
    }

    @Test
    void effectiveConfig_usesEnvironmentFallback_whenRowExistsButNotConfigured() {
        OutboundProxySettingsEntity row = new OutboundProxySettingsEntity();
        row.setConfigured(false);
        row.setEnabled(true);
        row.setHost("ignored.example.com");
        when(repository.findById(ID)).thenReturn(Optional.of(row));

        assertSame(fallback, service.effectiveConfig());
    }

    @Test
    void effectiveConfig_usesDatabase_whenConfigured() {
        OutboundProxySettingsEntity row = new OutboundProxySettingsEntity();
        row.setConfigured(true);
        row.setEnabled(true);
        row.setHost("ui-proxy.example.com");
        row.setPort(3128);
        row.setUsername("uiuser");
        row.setPassword("uipass");
        row.setNonProxyHosts("localhost, *.internal.example.com");
        when(repository.findById(ID)).thenReturn(Optional.of(row));

        OutboundProxyProperties effective = service.effectiveConfig();

        assertTrue(effective.enabled());
        assertEquals("ui-proxy.example.com", effective.host());
        assertEquals(3128, effective.port());
        assertEquals("uiuser", effective.username());
        assertEquals("uipass", effective.password());
        assertEquals(List.of("localhost", "*.internal.example.com"), effective.nonProxyHosts());
    }

    @Test
    void save_persistsConfiguredFlagAndAppliesToClient() {
        when(repository.findById(ID)).thenReturn(Optional.empty());

        service.save(true, "ui-proxy.example.com", 3128, "uiuser", "uipass",
                "localhost,*.internal.example.com");

        ArgumentCaptor<OutboundProxySettingsEntity> saved =
                ArgumentCaptor.forClass(OutboundProxySettingsEntity.class);
        verify(repository).save(saved.capture());
        assertTrue(saved.getValue().isConfigured(), "saving via UI must set configured=true");
        assertEquals("ui-proxy.example.com", saved.getValue().getHost());
        assertEquals("uipass", saved.getValue().getPassword());

        ArgumentCaptor<OutboundProxyProperties> applied =
                ArgumentCaptor.forClass(OutboundProxyProperties.class);
        verify(remoteHttpClient).applyRuntimeConfig(applied.capture());
        assertEquals("ui-proxy.example.com", applied.getValue().host());
        assertEquals(List.of("localhost", "*.internal.example.com"), applied.getValue().nonProxyHosts());
    }

    @Test
    void save_blankPassword_keepsStoredPassword() {
        OutboundProxySettingsEntity existing = new OutboundProxySettingsEntity();
        existing.setConfigured(true);
        existing.setPassword("previously-stored");
        when(repository.findById(ID)).thenReturn(Optional.of(existing));

        // Update with a blank password -> stored secret must be retained.
        service.save(true, "ui-proxy.example.com", 3128, "uiuser", "  ", null);

        ArgumentCaptor<OutboundProxySettingsEntity> saved =
                ArgumentCaptor.forClass(OutboundProxySettingsEntity.class);
        verify(repository).save(saved.capture());
        assertEquals("previously-stored", saved.getValue().getPassword(),
                "a blank password must not wipe the stored one (write-only field)");
    }

    @Test
    void save_newPassword_replacesStoredPassword() {
        OutboundProxySettingsEntity existing = new OutboundProxySettingsEntity();
        existing.setConfigured(true);
        existing.setPassword("old");
        when(repository.findById(ID)).thenReturn(Optional.of(existing));

        service.save(true, "ui-proxy.example.com", 3128, "uiuser", "fresh-secret", null);

        ArgumentCaptor<OutboundProxySettingsEntity> saved =
                ArgumentCaptor.forClass(OutboundProxySettingsEntity.class);
        verify(repository).save(saved.capture());
        assertEquals("fresh-secret", saved.getValue().getPassword());
    }

    @Test
    void applyOnStartup_pushesEffectiveConfigToClient() {
        when(repository.findById(ID)).thenReturn(Optional.empty());

        service.applyOnStartup();

        verify(remoteHttpClient).applyRuntimeConfig(fallback);
    }

    @Test
    void applyOnStartup_swallowsResolutionErrors() {
        when(repository.findById(ID)).thenThrow(new RuntimeException("db down"));

        // Must not propagate — the app must still start on the constructor-built client.
        service.applyOnStartup();

        verify(remoteHttpClient, never()).applyRuntimeConfig(any());
    }

    @Test
    void parseNonProxyHosts_trimsAndDropsBlanks() {
        assertEquals(List.of(), OutboundProxySettingsService.parseNonProxyHosts(null));
        assertEquals(List.of(), OutboundProxySettingsService.parseNonProxyHosts("  "));
        assertEquals(
                List.of("localhost", "*.internal.example.com"),
                OutboundProxySettingsService.parseNonProxyHosts(" localhost , ,*.internal.example.com "));
    }

    @Test
    void blankToNull_normalizesHostAndUsername() {
        when(repository.findById(ID)).thenReturn(Optional.empty());

        service.save(false, "   ", 3128, "", null, "");

        ArgumentCaptor<OutboundProxySettingsEntity> saved =
                ArgumentCaptor.forClass(OutboundProxySettingsEntity.class);
        verify(repository).save(saved.capture());
        assertFalse(saved.getValue().isEnabled());
        assertEquals(null, saved.getValue().getHost());
        assertEquals(null, saved.getValue().getUsername());
        assertEquals(null, saved.getValue().getNonProxyHosts());
    }
}
