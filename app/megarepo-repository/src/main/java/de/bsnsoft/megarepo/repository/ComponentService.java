package de.bsnsoft.megarepo.repository;

import de.bsnsoft.megarepo.core.event.ComponentCreatedEvent;
import de.bsnsoft.megarepo.core.format.ComponentCoordinates;
import de.bsnsoft.megarepo.database.entity.ComponentEntity;
import de.bsnsoft.megarepo.database.repository.ComponentJpaRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class ComponentService {

    private final ComponentJpaRepository componentJpaRepository;
    private final ApplicationEventPublisher eventPublisher;

    public ComponentService(
            ComponentJpaRepository componentJpaRepository, ApplicationEventPublisher eventPublisher) {
        this.componentJpaRepository = componentJpaRepository;
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
}
