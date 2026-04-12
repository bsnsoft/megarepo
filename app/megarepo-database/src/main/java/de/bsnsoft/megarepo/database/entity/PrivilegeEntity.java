package de.bsnsoft.megarepo.database.entity;

import de.bsnsoft.megarepo.database.converter.JsonbConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Entity
@Table(name = "privileges")
public class PrivilegeEntity {

    @Id
    @Column(name = "name", length = 200)
    private String name;

    @Column(name = "type", nullable = false, length = 50)
    private String type;

    @Column(name = "description")
    private String description;

    @Column(name = "read_only", nullable = false)
    private boolean readOnly = false;

    @Convert(converter = JsonbConverter.class)
    @Column(name = "properties", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> properties = new HashMap<>();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public PrivilegeEntity() {
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    public void setReadOnly(boolean readOnly) {
        this.readOnly = readOnly;
    }

    public Map<String, Object> getProperties() {
        return properties;
    }

    public void setProperties(Map<String, Object> properties) {
        this.properties = properties;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
