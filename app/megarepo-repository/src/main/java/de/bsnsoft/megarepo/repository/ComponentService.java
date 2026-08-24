package de.bsnsoft.megarepo.repository;

import de.bsnsoft.megarepo.core.event.ComponentCreatedEvent;
import de.bsnsoft.megarepo.core.format.ComponentCoordinates;
import de.bsnsoft.megarepo.database.entity.AssetEntity;
import de.bsnsoft.megarepo.database.entity.ComponentEntity;
import de.bsnsoft.megarepo.database.repository.AssetJpaRepository;
import de.bsnsoft.megarepo.database.repository.ComponentJpaRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ComponentService {

    private final ComponentJpaRepository componentJpaRepository;
    private final AssetJpaRepository assetJpaRepository;
    private final AssetService assetService;
    private final ApplicationEventPublisher eventPublisher;

    public ComponentService(
            ComponentJpaRepository componentJpaRepository,
            AssetJpaRepository assetJpaRepository,
            AssetService assetService,
            ApplicationEventPublisher eventPublisher) {
        this.componentJpaRepository = componentJpaRepository;
        this.assetJpaRepository = assetJpaRepository;
        this.assetService = assetService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public ComponentEntity findOrCreate(UUID repoId, String format, ComponentCoordinates coords) {
        Optional<ComponentEntity> existing =
                componentJpaRepository.findByRepositoryIdAndNamespaceAndNameAndVersion(
                        repoId, coords.namespace(), coords.name(), coords.version());
        if (existing.isPresent()) {
            return existing.get();
        }

        var component = new ComponentEntity();
        component.setRepositoryId(repoId);
        component.setFormat(format);
        component.setNamespace(coords.namespace());
        component.setName(coords.name());
        component.setVersion(coords.version());
        component.setCreatedAt(Instant.now());
        component.setUpdatedAt(Instant.now());

        ComponentEntity saved = componentJpaRepository.save(component);
        eventPublisher.publishEvent(new ComponentCreatedEvent(this, saved.getId(), repoId));
        return saved;
    }

    public Page<ComponentEntity> findByRepository(UUID repoId, Pageable pageable) {
        return componentJpaRepository.findByRepositoryId(repoId, pageable);
    }

    public Optional<ComponentEntity> findById(UUID componentId) {
        return componentJpaRepository.findById(componentId);
    }

    @Transactional
    public void delete(UUID componentId) {
        componentJpaRepository.deleteById(componentId);
    }

    /**
     * Delete a component together with all of its assets, removing the backing
     * blobs from storage as well as the database rows. This is the consistent
     * deletion path used by the management API — unlike {@link #delete(UUID)},
     * which only removes the component row and would orphan its assets and leak
     * their blobs.
     *
     * @return {@code true} if the component existed and was deleted
     */
    @Transactional
    public boolean deleteComponentWithAssets(UUID componentId) {
        Optional<ComponentEntity> maybeComponent = componentJpaRepository.findById(componentId);
        if (maybeComponent.isEmpty()) {
            return false;
        }

        // Delete every asset (blob + row) belonging to this component. Page through
        // in case a component has many assets (e.g. multi-artifact Maven components).
        int page = 0;
        List<AssetEntity> batch;
        do {
            batch = assetJpaRepository
                    .findByComponentId(componentId, PageRequest.of(page, 100))
                    .getContent();
            for (AssetEntity asset : batch) {
                assetService.deleteAsset(asset.getId());
            }
            // We always re-read page 0 because deletions shift the result set.
        } while (!batch.isEmpty());

        componentJpaRepository.deleteById(componentId);
        return true;
    }
}
