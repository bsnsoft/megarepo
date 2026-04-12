package de.bsnsoft.megarepo.core.event;

import org.springframework.context.ApplicationEvent;

import java.util.UUID;

public class RepositoryChangedEvent extends ApplicationEvent {

    private final UUID repositoryId;
    private final String action;

    public RepositoryChangedEvent(Object source, UUID repositoryId, String action) {
        super(source);
        this.repositoryId = repositoryId;
        this.action = action;
    }

    public UUID getRepositoryId() {
        return repositoryId;
    }

    public String getAction() {
        return action;
    }
}
