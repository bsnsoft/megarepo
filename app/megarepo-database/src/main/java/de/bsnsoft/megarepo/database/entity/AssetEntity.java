package de.bsnsoft.megarepo.database.entity;

import de.bsnsoft.megarepo.database.converter.JsonbConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "assets")
public class AssetEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(name = "id")
    private UUID id;

    @Column(name = "repository_id", nullable = false)
    private UUID repositoryId;

    @Column(name = "component_id")
    private UUID componentId;

    @Column(name = "format", nullable = false, length = 50)
    private String format;

    @Column(name = "path", nullable = false, length = 2048)
    private String path;

    @Column(name = "blob_ref", length = 500)
    private String blobRef;

    @Column(name = "content_type", length = 200)
    private String contentType;

    @Column(name = "size")
    private Long size;

    @Column(name = "checksum_md5", length = 32)
    private String checksumMd5;

    @Column(name = "checksum_sha1", length = 40)
    private String checksumSha1;

    @Column(name = "checksum_sha256", length = 64)
    private String checksumSha256;

    @Column(name = "checksum_sha512", length = 128)
    private String checksumSha512;

    @Column(name = "generated", nullable = false)
    private boolean generated = false;

    @Column(name = "last_downloaded")
    private Instant lastDownloaded;

    @Column(name = "last_modified", nullable = false)
    private Instant lastModified;

    @Column(name = "created_by", length = 200)
    private String createdBy;

    @Column(name = "created_by_ip", length = 45)
    private String createdByIp;

    @Convert(converter = JsonbConverter.class)
    @Column(name = "attributes", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> attributes = new HashMap<>();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public AssetEntity() {
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getRepositoryId() {
        return repositoryId;
    }

    public void setRepositoryId(UUID repositoryId) {
        this.repositoryId = repositoryId;
    }

    public UUID getComponentId() {
        return componentId;
    }

    public void setComponentId(UUID componentId) {
        this.componentId = componentId;
    }

    public String getFormat() {
        return format;
    }

    public void setFormat(String format) {
        this.format = format;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getBlobRef() {
        return blobRef;
    }

    public void setBlobRef(String blobRef) {
        this.blobRef = blobRef;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public Long getSize() {
        return size;
    }

    public void setSize(Long size) {
        this.size = size;
    }

    public String getChecksumMd5() {
        return checksumMd5;
    }

    public void setChecksumMd5(String checksumMd5) {
        this.checksumMd5 = checksumMd5;
    }

    public String getChecksumSha1() {
        return checksumSha1;
    }

    public void setChecksumSha1(String checksumSha1) {
        this.checksumSha1 = checksumSha1;
    }

    public String getChecksumSha256() {
        return checksumSha256;
    }

    public void setChecksumSha256(String checksumSha256) {
        this.checksumSha256 = checksumSha256;
    }

    public String getChecksumSha512() {
        return checksumSha512;
    }

    public void setChecksumSha512(String checksumSha512) {
        this.checksumSha512 = checksumSha512;
    }

    public boolean isGenerated() {
        return generated;
    }

    public void setGenerated(boolean generated) {
        this.generated = generated;
    }

    public Instant getLastDownloaded() {
        return lastDownloaded;
    }

    public void setLastDownloaded(Instant lastDownloaded) {
        this.lastDownloaded = lastDownloaded;
    }

    public Instant getLastModified() {
        return lastModified;
    }

    public void setLastModified(Instant lastModified) {
        this.lastModified = lastModified;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public String getCreatedByIp() {
        return createdByIp;
    }

    public void setCreatedByIp(String createdByIp) {
        this.createdByIp = createdByIp;
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }

    public void setAttributes(Map<String, Object> attributes) {
        this.attributes = attributes;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
