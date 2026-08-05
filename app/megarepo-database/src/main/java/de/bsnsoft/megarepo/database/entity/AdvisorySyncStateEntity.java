package de.bsnsoft.megarepo.database.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Ingestion state of one advisory source. Unlike {@link NvdSyncStateEntity},
 * which is a single row, this is keyed by source name — Phase 1 ingests from
 * several feeds.
 */
@Entity
@Table(name = "advisory_sync_state")
public class AdvisorySyncStateEntity {

    @Id
    @Column(name = "source", length = 30)
    private String source;

    @Column(name = "status", nullable = false, length = 20)
    private String status = "IDLE";

    /** Opaque, source-specific resume token. Never parsed by the firewall. */
    @Column(name = "cursor", length = 500)
    private String cursor;

    @Column(name = "last_success_at")
    private Instant lastSuccessAt;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public AdvisorySyncStateEntity() {}

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getCursor() { return cursor; }
    public void setCursor(String cursor) { this.cursor = cursor; }
    public Instant getLastSuccessAt() { return lastSuccessAt; }
    public void setLastSuccessAt(Instant lastSuccessAt) { this.lastSuccessAt = lastSuccessAt; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
