package de.bsnsoft.megarepo.database.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "nvd_firewall_settings")
public class NvdFirewallSettingsEntity {

    @Id
    @Column(name = "id")
    private Integer id = 1;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = false;

    @Column(name = "api_key", length = 200)
    private String apiKey;

    @Column(name = "cvss_threshold", nullable = false)
    private double cvssThreshold = 7.0;

    public NvdFirewallSettingsEntity() {}

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public double getCvssThreshold() { return cvssThreshold; }
    public void setCvssThreshold(double cvssThreshold) { this.cvssThreshold = cvssThreshold; }
}
