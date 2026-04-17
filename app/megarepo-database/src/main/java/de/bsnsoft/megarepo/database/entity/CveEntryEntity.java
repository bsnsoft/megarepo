package de.bsnsoft.megarepo.database.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "cve_entries")
public class CveEntryEntity {

    @Id
    @Column(name = "cve_id", length = 30)
    private String cveId;

    @Column(name = "published", nullable = false)
    private Instant published;

    @Column(name = "last_modified", nullable = false)
    private Instant lastModified;

    @Column(name = "cvss_score", nullable = false)
    private double cvssScore;

    @Column(name = "cvss_version", length = 10)
    private String cvssVersion;

    @Column(name = "severity", length = 20)
    private String severity;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    public CveEntryEntity() {}

    public String getCveId() { return cveId; }
    public void setCveId(String cveId) { this.cveId = cveId; }
    public Instant getPublished() { return published; }
    public void setPublished(Instant published) { this.published = published; }
    public Instant getLastModified() { return lastModified; }
    public void setLastModified(Instant lastModified) { this.lastModified = lastModified; }
    public double getCvssScore() { return cvssScore; }
    public void setCvssScore(double cvssScore) { this.cvssScore = cvssScore; }
    public String getCvssVersion() { return cvssVersion; }
    public void setCvssVersion(String cvssVersion) { this.cvssVersion = cvssVersion; }
    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
