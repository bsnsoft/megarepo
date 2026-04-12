package de.bsnsoft.megarepo.database.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ssl_certificates")
public class SslCertificateEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(name = "id")
    private UUID id;

    @Column(name = "pem", nullable = false, columnDefinition = "TEXT")
    private String pem;

    @Column(name = "subject_cn", length = 500)
    private String subjectCn;

    @Column(name = "issuer_cn", length = 500)
    private String issuerCn;

    @Column(name = "issuer_org", length = 500)
    private String issuerOrg;

    @Column(name = "fingerprint", nullable = false, unique = true, length = 100)
    private String fingerprint;

    @Column(name = "issued_on")
    private Instant issuedOn;

    @Column(name = "expires_on")
    private Instant expiresOn;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public SslCertificateEntity() {}

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getPem() {
        return pem;
    }

    public void setPem(String pem) {
        this.pem = pem;
    }

    public String getSubjectCn() {
        return subjectCn;
    }

    public void setSubjectCn(String subjectCn) {
        this.subjectCn = subjectCn;
    }

    public String getIssuerCn() {
        return issuerCn;
    }

    public void setIssuerCn(String issuerCn) {
        this.issuerCn = issuerCn;
    }

    public String getIssuerOrg() {
        return issuerOrg;
    }

    public void setIssuerOrg(String issuerOrg) {
        this.issuerOrg = issuerOrg;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public void setFingerprint(String fingerprint) {
        this.fingerprint = fingerprint;
    }

    public Instant getIssuedOn() {
        return issuedOn;
    }

    public void setIssuedOn(Instant issuedOn) {
        this.issuedOn = issuedOn;
    }

    public Instant getExpiresOn() {
        return expiresOn;
    }

    public void setExpiresOn(Instant expiresOn) {
        this.expiresOn = expiresOn;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
