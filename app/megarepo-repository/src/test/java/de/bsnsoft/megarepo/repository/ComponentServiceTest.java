package de.bsnsoft.megarepo.repository;

import de.bsnsoft.megarepo.core.format.ComponentCoordinates;
import de.bsnsoft.megarepo.database.entity.AssetEntity;
import de.bsnsoft.megarepo.database.entity.ComponentEntity;
import de.bsnsoft.megarepo.database.repository.AssetJpaRepository;
import de.bsnsoft.megarepo.database.repository.ComponentJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ComponentServiceTest {

    @Mock
    private ComponentJpaRepository componentJpaRepository;

    @Mock
    private AssetJpaRepository assetJpaRepository;

    @Mock
    private AssetService assetService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private ComponentService service;

    @BeforeEach
    void setUp() {
        service = new ComponentService(
                componentJpaRepository, assetJpaRepository, assetService, eventPublisher);
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

    @Test
    void deleteComponentWithAssets_deletesAllAssetsAndComponent() {
        UUID componentId = UUID.randomUUID();
        var component = new ComponentEntity();
        component.setId(componentId);

        AssetEntity a1 = new AssetEntity();
        a1.setId(UUID.randomUUID());
        AssetEntity a2 = new AssetEntity();
        a2.setId(UUID.randomUUID());

        when(componentJpaRepository.findById(componentId)).thenReturn(Optional.of(component));
        // First page returns the two assets, second page is empty (loop terminates).
        Page<AssetEntity> firstPage = new PageImpl<>(List.of(a1, a2));
        Page<AssetEntity> emptyPage = new PageImpl<>(List.<AssetEntity>of());
        when(assetJpaRepository.findByComponentId(eq(componentId), any(Pageable.class)))
                .thenReturn(firstPage)
                .thenReturn(emptyPage);

        boolean result = service.deleteComponentWithAssets(componentId);

        assertTrue(result);
        verify(assetService).deleteAsset(a1.getId());
        verify(assetService).deleteAsset(a2.getId());
        verify(componentJpaRepository).deleteById(componentId);
    }

    @Test
    void deleteComponentWithAssets_returnsFalseWhenMissing() {
        UUID componentId = UUID.randomUUID();
        when(componentJpaRepository.findById(componentId)).thenReturn(Optional.empty());

        boolean result = service.deleteComponentWithAssets(componentId);

        assertFalse(result);
        verify(componentJpaRepository, never()).deleteById(any());
        verify(assetService, never()).deleteAsset(any());
    }
}
