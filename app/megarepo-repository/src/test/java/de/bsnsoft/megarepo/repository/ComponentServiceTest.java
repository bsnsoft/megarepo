package de.bsnsoft.megarepo.repository;

import de.bsnsoft.megarepo.core.format.ComponentCoordinates;
import de.bsnsoft.megarepo.database.entity.ComponentEntity;
import de.bsnsoft.megarepo.database.repository.ComponentJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ComponentServiceTest {

    @Mock
    private ComponentJpaRepository componentJpaRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private ComponentService service;

    @BeforeEach
    void setUp() {
        service = new ComponentService(componentJpaRepository, eventPublisher);
    }

    @Test
    void findOrCreate_existingComponent() {
        UUID repoId = UUID.randomUUID();
        var coords = new ComponentCoordinates("com.example", "lib", "2.0", Map.of());
        var existing = new ComponentEntity();
        existing.setId(UUID.randomUUID());
        existing.setRepositoryId(repoId);
        existing.setNamespace("com.example");
        existing.setName("lib");
        existing.setVersion("2.0");
        existing.setCreatedAt(Instant.now());
        existing.setUpdatedAt(Instant.now());

        when(componentJpaRepository.findByRepositoryIdAndNamespaceAndNameAndVersion(
                        repoId, "com.example", "lib", "2.0"))
                .thenReturn(Optional.of(existing));

        ComponentEntity result = service.findOrCreate(repoId, "maven2", coords);

        assertEquals(existing.getId(), result.getId());
        verify(componentJpaRepository, never()).save(any());
    }

    @Test
    void findOrCreate_newComponent() {
        UUID repoId = UUID.randomUUID();
        var coords = new ComponentCoordinates("com.example", "new-lib", "1.0", Map.of());

        when(componentJpaRepository.findByRepositoryIdAndNamespaceAndNameAndVersion(
                        repoId, "com.example", "new-lib", "1.0"))
                .thenReturn(Optional.empty());

        var savedComponent = new ComponentEntity();
        savedComponent.setId(UUID.randomUUID());
        savedComponent.setRepositoryId(repoId);
        savedComponent.setFormat("maven2");
        savedComponent.setNamespace("com.example");
        savedComponent.setName("new-lib");
        savedComponent.setVersion("1.0");
        savedComponent.setCreatedAt(Instant.now());
        savedComponent.setUpdatedAt(Instant.now());

        when(componentJpaRepository.save(any(ComponentEntity.class))).thenReturn(savedComponent);

        ComponentEntity result = service.findOrCreate(repoId, "maven2", coords);

        assertNotNull(result);
        assertEquals("new-lib", result.getName());

        ArgumentCaptor<ComponentEntity> captor = ArgumentCaptor.forClass(ComponentEntity.class);
        verify(componentJpaRepository).save(captor.capture());
        ComponentEntity saved = captor.getValue();
        assertEquals(repoId, saved.getRepositoryId());
        assertEquals("maven2", saved.getFormat());
        assertEquals("com.example", saved.getNamespace());
        assertEquals("new-lib", saved.getName());
        assertEquals("1.0", saved.getVersion());

        verify(eventPublisher).publishEvent(any());
    }

    @Test
    void delete_delegatesToRepository() {
        UUID id = UUID.randomUUID();
        service.delete(id);
        verify(componentJpaRepository).deleteById(id);
    }
}
