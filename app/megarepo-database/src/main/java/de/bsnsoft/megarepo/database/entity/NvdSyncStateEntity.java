package de.bsnsoft.megarepo.database.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "nvd_sync_state")
public class NvdSyncStateEntity {

    @Id
    @Column(name = "id")
    private Integer id = 1;

    @Column(name = "status", nullable = false, length = 20)
    private String status = "IDLE";

    @Column(name = "mode", length = 20)
    private String mode;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "last_sync_at")
    private Instant lastSyncAt;

    @Column(name = "last_success_at")
    private Instant lastSuccessAt;

    @Column(name = "total_cves", nullable = false)
    private int totalCves;

    @Column(name = "synced_cves", nullable = false)
    private int syncedCves;

    @Column(name = "total_results")
    private Integer totalResults;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    public NvdSyncStateEntity() {}

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getLastSyncAt() { return lastSyncAt; }
    public void setLastSyncAt(Instant lastSyncAt) { this.lastSyncAt = lastSyncAt; }
    public Instant getLastSuccessAt() { return lastSuccessAt; }
    public void setLastSuccessAt(Instant lastSuccessAt) { this.lastSuccessAt = lastSuccessAt; }
    public int getTotalCves() { return totalCves; }
    public void setTotalCves(int totalCves) { this.totalCves = totalCves; }
    public int getSyncedCves() { return syncedCves; }
    public void setSyncedCves(int syncedCves) { this.syncedCves = syncedCves; }
    public Integer getTotalResults() { return totalResults; }
    public void setTotalResults(Integer totalResults) { this.totalResults = totalResults; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}
