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
@Table(name = "routing_rules")
public class RoutingRuleEntity {

    @Id
    @Column(name = "name", length = 200)
    private String name;

    @Column(name = "description")
    private String description;

    @Column(name = "mode", nullable = false, length = 20)
    private String mode = "BLOCK";

    @Convert(converter = JsonbConverter.class)
    @Column(name = "matchers", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> matchers = new HashMap<>();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public RoutingRuleEntity() {
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public Map<String, Object> getMatchers() {
        return matchers;
    }

    public void setMatchers(Map<String, Object> matchers) {
        this.matchers = matchers;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
