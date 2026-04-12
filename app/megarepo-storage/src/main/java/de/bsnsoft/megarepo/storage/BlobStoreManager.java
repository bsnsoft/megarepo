package de.bsnsoft.megarepo.storage;

import de.bsnsoft.megarepo.core.storage.BlobStore;
import de.bsnsoft.megarepo.core.storage.BlobStoreMetrics;
import de.bsnsoft.megarepo.core.storage.BlobStoreType;
import de.bsnsoft.megarepo.storage.file.FileBlobStore;
import de.bsnsoft.megarepo.storage.s3.S3BlobStore;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class BlobStoreManager {

    private final Map<String, BlobStore> stores = new ConcurrentHashMap<>();

    public BlobStore get(String name) {
        BlobStore store = stores.get(name);
        if (store == null) {
            throw new IllegalArgumentException("Blob store not found: " + name);
        }
        return store;
    }

    public BlobStore create(String name, BlobStoreType type, Map<String, Object> config) {
        BlobStore store = switch (type) {
            case FILE -> new FileBlobStore(name);
            case S3 -> new S3BlobStore(name);
        };
        store.init(config);
        stores.put(name, store);
        return store;
    }

    public void delete(String name) {
        stores.remove(name);
    }

    public List<BlobStoreInfo> list() {
        return stores.values().stream()
                .map(store -> new BlobStoreInfo(store.getName(), store.getType(), store.getMetrics()))
                .toList();
    }

    public record BlobStoreInfo(String name, BlobStoreType type, BlobStoreMetrics metrics) {
    }
}
