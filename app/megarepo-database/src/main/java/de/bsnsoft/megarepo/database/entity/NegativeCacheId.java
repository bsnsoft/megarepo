package de.bsnsoft.megarepo.database.entity;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public class NegativeCacheId implements Serializable {

    private UUID repositoryId;
    private String path;

    public NegativeCacheId() {
    }

    public NegativeCacheId(UUID repositoryId, String path) {
        this.repositoryId = repositoryId;
        this.path = path;
    }

    public UUID getRepositoryId() {
        return repositoryId;
    }

    public void setRepositoryId(UUID repositoryId) {
        this.repositoryId = repositoryId;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        NegativeCacheId that = (NegativeCacheId) o;
        return Objects.equals(repositoryId, that.repositoryId)
                && Objects.equals(path, that.path);
    }

    @Override
    public int hashCode() {
        return Objects.hash(repositoryId, path);
    }
}
