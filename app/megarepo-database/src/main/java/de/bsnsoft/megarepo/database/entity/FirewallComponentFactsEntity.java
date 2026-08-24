package de.bsnsoft.megarepo.database.entity;

import de.bsnsoft.megarepo.core.firewall.FirewallFactsState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * What the ecosystem declares about one component version, mapped onto
 * {@code firewall_component_facts} (V17).
 *
 * <p>The cache that lets {@code MIN_AGE} and {@code LICENSE} be evaluated
 * without a network call on the request thread. The background resolver fills
 * it; the request path only reads it, and a rule that finds
 * {@link FirewallFactsState#UNKNOWN} or {@link FirewallFactsState#PENDING}
 * reports "indeterminate" rather than guessing.
 *
 * <p>Keyed on the qualifier-free purl coordinates, not on a component id: the
 * release date of a package version is one fact, not one per repository that
 * happens to have cached it.
 *
 * <p><b>Declared metadata only.</b> Nothing here comes from scanning file
 * contents — that is out of scope by design, and {@link #licenseSource} records
 * which declaration was read so a verdict can be argued with.
 */
@Entity
@Table(name = "firewall_component_facts")
public class FirewallComponentFactsEntity {

    /** Qualifier-free purl coordinates, e.g. {@code pkg:maven/com.acme/util@1.0}. */
    @Id
    @Column(name = "purl", length = 1000)
    private String purl;

    @Column(name = "purl_type", nullable = false, length = 50)
    private String purlType;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 20)
    private FirewallFactsState state = FirewallFactsState.UNKNOWN;

    /**
     * Upstream publication time of this version. Null with state
     * {@link FirewallFactsState#RESOLVED} means the metadata is genuinely silent,
     * which a MIN_AGE rule must treat as "cannot judge" rather than "brand new".
     */
    @Column(name = "published_at")
    private Instant publishedAt;

    /** SPDX ids where the metadata gives them, verbatim otherwise. */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "declared_licenses", nullable = false, columnDefinition = "text[]")
    private String[] declaredLicenses = new String[0];

    /** {@code PACKAGE_METADATA} or {@code UPSTREAM_REGISTRY}; never file contents. */
    @Column(name = "license_source", length = 20)
    private String licenseSource;

    /** Which resolver answered, e.g. {@code maven-pom}, {@code npm-registry}. */
    @Column(name = "source", length = 40)
    private String source;

    @Column(name = "fetched_at")
    private Instant fetchedAt;

    @Column(name = "attempts", nullable = false)
    private int attempts = 0;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public FirewallComponentFactsEntity() {}

    public String getPurl() { return purl; }
    public void setPurl(String purl) { this.purl = purl; }
    public String getPurlType() { return purlType; }
    public void setPurlType(String purlType) { this.purlType = purlType; }
    public FirewallFactsState getState() { return state; }
    public void setState(FirewallFactsState state) { this.state = state; }
    public Instant getPublishedAt() { return publishedAt; }
    public void setPublishedAt(Instant publishedAt) { this.publishedAt = publishedAt; }
    public String[] getDeclaredLicenses() { return declaredLicenses; }
    public void setDeclaredLicenses(String[] declaredLicenses) { this.declaredLicenses = declaredLicenses; }
    public String getLicenseSource() { return licenseSource; }
    public void setLicenseSource(String licenseSource) { this.licenseSource = licenseSource; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public Instant getFetchedAt() { return fetchedAt; }
    public void setFetchedAt(Instant fetchedAt) { this.fetchedAt = fetchedAt; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
