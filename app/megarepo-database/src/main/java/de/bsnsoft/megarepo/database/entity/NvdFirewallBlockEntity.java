package de.bsnsoft.megarepo.database.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "nvd_firewall_blocks")
public class NvdFirewallBlockEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "timestamp", nullable = false)
    private Instant timestamp;

    @Column(name = "user_id", length = 200)
    private String userId;

    @Column(name = "repository", nullable = false, length = 200)
    private String repository;

    @Column(name = "path", nullable = false, length = 2048)
    private String path;

    @Column(name = "component_key", nullable = false, length = 500)
    private String componentKey;

    @Column(name = "max_cvss_score", nullable = false)
    private double maxCvssScore;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "cve_details", nullable = false, columnDefinition = "jsonb")
    private List<Map<String, Object>> cveDetails = List.of();

    public NvdFirewallBlockEntity() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getRepository() { return repository; }
    public void setRepository(String repository) { this.repository = repository; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public String getComponentKey() { return componentKey; }
    public void setComponentKey(String componentKey) { this.componentKey = componentKey; }
    public double getMaxCvssScore() { return maxCvssScore; }
    public void setMaxCvssScore(double maxCvssScore) { this.maxCvssScore = maxCvssScore; }
    public List<Map<String, Object>> getCveDetails() { return cveDetails; }
    public void setCveDetails(List<Map<String, Object>> cveDetails) { this.cveDetails = cveDetails; }
}
