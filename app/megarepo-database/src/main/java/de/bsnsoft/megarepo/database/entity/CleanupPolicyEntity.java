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
@Table(name = "cleanup_policies")
public class CleanupPolicyEntity {

    @Id
    @Column(name = "name", length = 200)
    private String name;

    @Column(name = "format", length = 50)
    private String format;

    @Column(name = "notes")
    private String notes;

    @Convert(converter = JsonbConverter.class)
    @Column(name = "criteria", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> criteria = new HashMap<>();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public CleanupPolicyEntity() {
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getFormat() {
        return format;
    }

    public void setFormat(String format) {
        this.format = format;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public Map<String, Object> getCriteria() {
        return criteria;
    }

    public void setCriteria(Map<String, Object> criteria) {
        this.criteria = criteria;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
