package de.bsnsoft.megarepo.core.event;

import org.springframework.context.ApplicationEvent;

import java.util.UUID;

public class ComponentCreatedEvent extends ApplicationEvent {

    private final UUID componentId;
    private final UUID repositoryId;

    public ComponentCreatedEvent(Object source, UUID componentId, UUID repositoryId) {
        super(source);
        this.componentId = componentId;
        this.repositoryId = repositoryId;
    }

    public UUID getComponentId() {
        return componentId;
    }

    public UUID getRepositoryId() {
        return repositoryId;
    }
}
