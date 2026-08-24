package de.bsnsoft.megarepo.database.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A single advisory, normalised across sources. The id is the upstream
 * identifier (CVE-…, GHSA-…, OSV-…, MAL-…), so it is assigned, never generated.
 */
@Entity
@Table(name = "advisory")
public class AdvisoryEntity {

    @Id
    @Column(name = "id", length = 100)
    private String id;

    @Column(name = "source", nullable = false, length = 30)
    private String source;

    @Column(name = "summary", columnDefinition = "text")
    private String summary;

    @Column(name = "severity", length = 20)
    private String severity;

    /** Null when the source publishes no CVSS score (e.g. malicious-package advisories). */
    @Column(name = "cvss_score")
    private Double cvssScore;

    @Column(name = "cvss_vector", length = 200)
    private String cvssVector;

    @Column(name = "published")
    private Instant published;

    @Column(name = "modified")
    private Instant modified;

    @Column(name = "withdrawn_at")
    private Instant withdrawnAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public AdvisoryEntity() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }
    public Double getCvssScore() { return cvssScore; }
    public void setCvssScore(Double cvssScore) { this.cvssScore = cvssScore; }
    public String getCvssVector() { return cvssVector; }
    public void setCvssVector(String cvssVector) { this.cvssVector = cvssVector; }
    public Instant getPublished() { return published; }
    public void setPublished(Instant published) { this.published = published; }
    public Instant getModified() { return modified; }
    public void setModified(Instant modified) { this.modified = modified; }
    public Instant getWithdrawnAt() { return withdrawnAt; }
    public void setWithdrawnAt(Instant withdrawnAt) { this.withdrawnAt = withdrawnAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
