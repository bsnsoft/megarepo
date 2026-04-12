package de.bsnsoft.megarepo.core.event;

import org.springframework.context.ApplicationEvent;

import java.util.UUID;

public class AssetCreatedEvent extends ApplicationEvent {

    private final UUID assetId;
    private final UUID repositoryId;
    private final String path;

    public AssetCreatedEvent(Object source, UUID assetId, UUID repositoryId, String path) {
        super(source);
        this.assetId = assetId;
        this.repositoryId = repositoryId;
        this.path = path;
    }

    public UUID getAssetId() {
        return assetId;
    }

    public UUID getRepositoryId() {
        return repositoryId;
    }

    public String getPath() {
        return path;
    }
}
