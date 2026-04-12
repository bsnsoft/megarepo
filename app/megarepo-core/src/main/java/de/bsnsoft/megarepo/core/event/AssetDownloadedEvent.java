package de.bsnsoft.megarepo.core.event;

import org.springframework.context.ApplicationEvent;

import java.util.UUID;

public class AssetDownloadedEvent extends ApplicationEvent {

    private final UUID assetId;
    private final UUID repositoryId;

    public AssetDownloadedEvent(Object source, UUID assetId, UUID repositoryId) {
        super(source);
        this.assetId = assetId;
        this.repositoryId = repositoryId;
    }

    public UUID getAssetId() {
        return assetId;
    }

    public UUID getRepositoryId() {
        return repositoryId;
    }
}
