package de.bsnsoft.megarepo.core.storage;

import java.io.InputStream;
import java.util.Map;
import java.util.Optional;

public interface BlobStore {

    String getName();

    BlobStoreType getType();

    BlobRef store(InputStream data, Map<String, String> headers);

    BlobRef store(InputStream data, long size, Map<String, String> headers);

    Optional<Blob> get(BlobRef ref);

    boolean delete(BlobRef ref);

    boolean exists(BlobRef ref);

    BlobStoreMetrics getMetrics();

    void init(Map<String, Object> config);

    void compact();
}
