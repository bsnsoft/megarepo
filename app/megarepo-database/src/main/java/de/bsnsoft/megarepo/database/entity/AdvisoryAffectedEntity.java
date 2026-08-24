package de.bsnsoft.megarepo.database.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * One affected package range of an advisory, keyed by purl coordinates rather
 * than CPE. {@code purlNamespace} is null for formats without a namespace.
 */
@Entity
@Table(name = "advisory_affected")
public class AdvisoryAffectedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(name = "id")
    private UUID id;

    @Column(name = "advisory_id", nullable = false, length = 100)
    private String advisoryId;

    @Column(name = "purl_type", nullable = false, length = 50)
    private String purlType;

    @Column(name = "purl_namespace", length = 500)
    private String purlNamespace;

    @Column(name = "purl_name", nullable = false, length = 500)
    private String purlName;

    /** Raw upstream range expression, kept verbatim for traceability. */
    @Column(name = "version_range", length = 1000)
    private String versionRange;

    @Column(name = "introduced", length = 200)
    private String introduced;

    @Column(name = "fixed", length = 200)
    private String fixed;

    @Column(name = "last_affected", length = 200)
    private String lastAffected;

    public AdvisoryAffectedEntity() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getAdvisoryId() { return advisoryId; }
    public void setAdvisoryId(String advisoryId) { this.advisoryId = advisoryId; }
    public String getPurlType() { return purlType; }
    public void setPurlType(String purlType) { this.purlType = purlType; }
    public String getPurlNamespace() { return purlNamespace; }
    public void setPurlNamespace(String purlNamespace) { this.purlNamespace = purlNamespace; }
    public String getPurlName() { return purlName; }
    public void setPurlName(String purlName) { this.purlName = purlName; }
    public String getVersionRange() { return versionRange; }
    public void setVersionRange(String versionRange) { this.versionRange = versionRange; }
    public String getIntroduced() { return introduced; }
    public void setIntroduced(String introduced) { this.introduced = introduced; }
    public String getFixed() { return fixed; }
    public void setFixed(String fixed) { this.fixed = fixed; }
    public String getLastAffected() { return lastAffected; }
    public void setLastAffected(String lastAffected) { this.lastAffected = lastAffected; }
}
