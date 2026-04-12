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
@Table(name = "scheduled_tasks")
public class ScheduledTaskEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(name = "id")
    private UUID id;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "type", nullable = false, length = 200)
    private String type;

    @Column(name = "cron_expression", length = 200)
    private String cronExpression;

    @Convert(converter = JsonbConverter.class)
    @Column(name = "config", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> config = new HashMap<>();

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "current_state", nullable = false, length = 50)
    private String currentState = "WAITING";

    @Column(name = "last_run")
    private Instant lastRun;

    @Column(name = "last_run_result", length = 50)
    private String lastRunResult;

    @Column(name = "next_run")
    private Instant nextRun;

    @Column(name = "message")
    private String message;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public ScheduledTaskEntity() {
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
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

    public String getCronExpression() {
        return cronExpression;
    }

    public void setCronExpression(String cronExpression) {
        this.cronExpression = cronExpression;
    }

    public Map<String, Object> getConfig() {
        return config;
    }

    public void setConfig(Map<String, Object> config) {
        this.config = config;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getCurrentState() {
        return currentState;
    }

    public void setCurrentState(String currentState) {
        this.currentState = currentState;
    }

    public Instant getLastRun() {
        return lastRun;
    }

    public void setLastRun(Instant lastRun) {
        this.lastRun = lastRun;
    }

    public String getLastRunResult() {
        return lastRunResult;
    }

    public void setLastRunResult(String lastRunResult) {
        this.lastRunResult = lastRunResult;
    }

    public Instant getNextRun() {
        return nextRun;
    }

    public void setNextRun(Instant nextRun) {
        this.nextRun = nextRun;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
