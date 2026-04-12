package de.bsnsoft.megarepo.storage.file;

import java.nio.file.Path;
import java.util.Map;

public record FileBlobStoreConfig(Path basePath) {

    public static FileBlobStoreConfig fromMap(Map<String, Object> config) {
        return new FileBlobStoreConfig(Path.of((String) config.get("path")));
    }
}
