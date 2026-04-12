package de.bsnsoft.megarepo.database.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "negative_cache")
@IdClass(NegativeCacheId.class)
public class NegativeCacheEntry {

    @Id
    @Column(name = "repository_id", nullable = false)
    private UUID repositoryId;

    @Id
    @Column(name = "path", nullable = false, length = 2048)
    private String path;

    @Column(name = "cached_at", nullable = false)
    private Instant cachedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    public NegativeCacheEntry() {
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

    public Instant getCachedAt() {
        return cachedAt;
    }

    public void setCachedAt(Instant cachedAt) {
        this.cachedAt = cachedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }
}
