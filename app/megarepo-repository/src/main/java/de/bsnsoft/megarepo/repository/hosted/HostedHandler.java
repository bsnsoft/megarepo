package de.bsnsoft.megarepo.repository.hosted;

import de.bsnsoft.megarepo.core.format.ComponentCoordinates;
import de.bsnsoft.megarepo.core.repository.RepositoryConfig;
import de.bsnsoft.megarepo.core.repository.WritePolicy;
import de.bsnsoft.megarepo.core.storage.Blob;
import de.bsnsoft.megarepo.database.entity.AssetEntity;
import de.bsnsoft.megarepo.repository.AssetService;
import de.bsnsoft.megarepo.repository.ComponentService;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.Map;
import java.util.Optional;

@Service
public class HostedHandler {

    private final AssetService assetService;
    private final ComponentService componentService;

    public HostedHandler(AssetService assetService, ComponentService componentService) {
        this.assetService = assetService;
        this.componentService = componentService;
    }

    @SuppressWarnings("unchecked")
    public AssetEntity storeAsset(
            RepositoryConfig repo,
            String path,
            InputStream content,
            String contentType,
            ComponentCoordinates coords,
            String user,
            String ip) {
        WritePolicy writePolicy = resolveWritePolicy(repo);

        if (writePolicy == WritePolicy.DENY) {
            throw new WriteNotAllowedException("Repository '%s' is read-only".formatted(repo.name()));
        }

        if (writePolicy == WritePolicy.ALLOW_ONCE) {
            Optional<AssetEntity> existing = assetService.getAsset(repo.id(), path);
            if (existing.isPresent()) {
                throw new WriteNotAllowedException(
                        "Asset '%s' already exists in repository '%s' (write policy: ALLOW_ONCE)"
                                .formatted(path, repo.name()));
            }
        }

        var component = componentService.findOrCreate(repo.id(), repo.format(), coords);

        return assetService.createAsset(
                repo.id(),
                component.getId(),
                repo.format(),
                path,
                content,
                contentType,
                user,
                ip,
                repo.blobStoreName());
    }

    public Optional<Blob> getAsset(RepositoryConfig repo, String path) {
        Optional<AssetEntity> asset = assetService.getAsset(repo.id(), path);
        if (asset.isEmpty()) {
            return Optional.empty();
        }
        AssetEntity entity = asset.get();
        assetService.updateLastDownloaded(entity.getId(), repo.id());
        return assetService.getAssetContent(entity);
    }

    public boolean deleteAsset(RepositoryConfig repo, String path) {
        Optional<AssetEntity> asset = assetService.getAsset(repo.id(), path);
        if (asset.isEmpty()) {
            return false;
        }
        return assetService.deleteAsset(asset.get().getId());
    }

    @SuppressWarnings("unchecked")
    private WritePolicy resolveWritePolicy(RepositoryConfig repo) {
        Object storageObj = repo.attributes().get("storage");
        if (storageObj instanceof Map<?, ?> storage) {
            Object policyObj = storage.get("writePolicy");
            if (policyObj instanceof String policyStr) {
                try {
                    return WritePolicy.valueOf(policyStr);
                } catch (IllegalArgumentException e) {
                    return WritePolicy.ALLOW;
                }
            }
        }
        return WritePolicy.ALLOW;
    }
}
