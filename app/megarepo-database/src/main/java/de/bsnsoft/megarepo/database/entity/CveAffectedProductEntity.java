package de.bsnsoft.megarepo.database.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "cve_affected_products")
public class CveAffectedProductEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "cve_id", nullable = false, length = 30)
    private String cveId;

    @Column(name = "vendor", length = 200)
    private String vendor;

    @Column(name = "product", nullable = false, length = 200)
    private String product;

    @Column(name = "version_exact", length = 200)
    private String versionExact;

    @Column(name = "version_start_including", length = 200)
    private String versionStartIncluding;

    @Column(name = "version_start_excluding", length = 200)
    private String versionStartExcluding;

    @Column(name = "version_end_including", length = 200)
    private String versionEndIncluding;

    @Column(name = "version_end_excluding", length = 200)
    private String versionEndExcluding;

    public CveAffectedProductEntity() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCveId() { return cveId; }
    public void setCveId(String cveId) { this.cveId = cveId; }
    public String getVendor() { return vendor; }
    public void setVendor(String vendor) { this.vendor = vendor; }
    public String getProduct() { return product; }
    public void setProduct(String product) { this.product = product; }
    public String getVersionExact() { return versionExact; }
    public void setVersionExact(String versionExact) { this.versionExact = versionExact; }
    public String getVersionStartIncluding() { return versionStartIncluding; }
    public void setVersionStartIncluding(String v) { this.versionStartIncluding = v; }
    public String getVersionStartExcluding() { return versionStartExcluding; }
    public void setVersionStartExcluding(String v) { this.versionStartExcluding = v; }
    public String getVersionEndIncluding() { return versionEndIncluding; }
    public void setVersionEndIncluding(String v) { this.versionEndIncluding = v; }
    public String getVersionEndExcluding() { return versionEndExcluding; }
    public void setVersionEndExcluding(String v) { this.versionEndExcluding = v; }
}
