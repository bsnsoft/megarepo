package de.bsnsoft.megarepo.app.firewall;

import de.bsnsoft.megarepo.database.entity.AdvisoryAffectedEntity;
import de.bsnsoft.megarepo.database.entity.AdvisoryEntity;
import de.bsnsoft.megarepo.database.entity.ComponentEntity;
import de.bsnsoft.megarepo.database.entity.CveAffectedProductEntity;
import de.bsnsoft.megarepo.database.entity.CveEntryEntity;
import de.bsnsoft.megarepo.database.entity.RepositoryEntity;
import de.bsnsoft.megarepo.database.repository.AdvisoryAffectedJpaRepository;
import de.bsnsoft.megarepo.database.repository.AdvisoryJpaRepository;
import de.bsnsoft.megarepo.database.repository.ComponentJpaRepository;
import de.bsnsoft.megarepo.database.repository.CveAffectedProductJpaRepository;
import de.bsnsoft.megarepo.database.repository.CveEntryJpaRepository;
import de.bsnsoft.megarepo.database.repository.RepositoryJpaRepository;

import java.time.Instant;
import java.util.UUID;

/**
 * A hand-built repository and advisory store covering every case the comparison
 * report distinguishes.
 *
 * <h2>This data is synthetic and says nothing about frequency</h2>
 *
 * It exists to prove that the comparison sorts each case into the right bucket,
 * and to show what the output looks like. It is <em>not</em> a sample: the ratio
 * of false positives to agreements here is chosen by the author, and any
 * conclusion about how often each case occurs must come from a run over real
 * repository data. Every report produced from it is labelled synthetic.
 *
 * <h2>Which parts are real</h2>
 *
 * The two Apache cases use the genuine advisory identifiers and version ranges
 * (Log4Shell {@code CVE-2021-44228}, {@code [2.0-beta9, 2.15.0)}; Text4Shell
 * {@code CVE-2022-42889} / {@code GHSA-599f-7c49-w659}, {@code [1.5, 1.10.0)}),
 * because the defects they demonstrate — a CPE product that is coarser than the
 * artifact, and the same vulnerability published under two ids — are properties
 * of that real data. Everything else is invented and carries a
 * {@code CVE-2024-110xx} id that does not exist upstream.
 *
 * <h2>The eleven components</h2>
 *
 * <table>
 *   <caption>Expected classification</caption>
 *   <tr><th>#</th><th>Component</th><th>Expected</th><th>Why</th></tr>
 *   <tr><td>1</td><td>{@code com.acme:util@1.0}</td><td>AGREED</td>
 *       <td>Both methods find CVE-2024-11001.</td></tr>
 *   <tr><td>2</td><td>{@code org.other:util@1.0}</td><td>CPE_ONLY</td>
 *       <td>Same artifact name, different publisher. The legacy lookup never
 *           reads the namespace, so it matches the CPE product {@code util} that
 *           belongs to {@code com.acme}'s package.</td></tr>
 *   <tr><td>3</td><td>{@code org.apache.logging.log4j:log4j-api@2.14.1}</td>
 *       <td>CPE_ONLY</td>
 *       <td>The "first segment before the dash" rule folds {@code log4j-api}
 *           onto the CPE product {@code log4j}. Log4Shell only ever affected
 *           {@code log4j-core}.</td></tr>
 *   <tr><td>4</td><td>{@code org.apache.logging.log4j:log4j-core@2.14.1}</td>
 *       <td>AGREED</td><td>The folding is harmless here — this artifact really
 *           is affected.</td></tr>
 *   <tr><td>5</td><td>{@code org.apache.commons:commons-text@1.9}</td>
 *       <td>CPE_ONLY + PURL_ONLY</td>
 *       <td>Text4Shell, published by NVD as {@code CVE-2022-42889} and by GitHub
 *           as {@code GHSA-599f-7c49-w659}. Phase 1 has no alias table, so one
 *           vulnerability appears on both sides of the diff. Reported as-is
 *           rather than hidden: it is a real limitation of the replacement.</td></tr>
 *   <tr><td>6</td><td>{@code org.apache.commons:commons-compress@1.21-sp1}</td>
 *       <td>VERSION_ONLY_CPE</td>
 *       <td>A Maven service pack of the fixed release. Maven orders {@code sp1}
 *           above the plain release; the generic comparator orders every
 *           qualifier below it and keeps flagging the patched build.</td></tr>
 *   <tr><td>7</td><td>{@code pypi pillow@9.0.1.post1}</td><td>VERSION_ONLY_CPE</td>
 *       <td>The PEP 440 counterpart: {@code .post1} is newer than the fix, the
 *           generic comparator reads it as a text segment and sorts it below.</td></tr>
 *   <tr><td>8</td><td>{@code com.acme:widget@1.0-alpha10}</td>
 *       <td>VERSION_ONLY_PURL</td>
 *       <td>The mirror image, and the case where the current firewall is
 *           <em>permissive</em>: it compares {@code alpha10} against
 *           {@code alpha9} as text, decides the component is below the affected
 *           range and lets a vulnerable build through.</td></tr>
 *   <tr><td>9</td><td>{@code com.acme:safe@3.0}</td><td>BOTH_CLEAN</td>
 *       <td>No advisory anywhere.</td></tr>
 *   <tr><td>10</td><td>{@code raw vendor-drops:struts2@1}</td>
 *       <td>UNIDENTIFIED + CPE_ONLY</td>
 *       <td>A raw file has no package coordinates. The legacy path still matches
 *           its file name against a CPE product; the replacement reports nothing
 *           at all, which is a loss of coverage rather than a proven false
 *           positive.</td></tr>
 *   <tr><td>11</td><td>{@code io.example:orphan-lib@1.2.3}</td><td>PURL_ONLY</td>
 *       <td>Published only as a GHSA against the purl. No CPE product name the
 *           legacy candidate generation produces reaches it, so the running
 *           firewall misses it entirely.</td></tr>
 * </table>
 *
 * <p>Every CVE in the fixture also exists as a CPE-derived row in
 * {@code advisory_affected} under the reserved {@code cpe} purl type, because
 * that is what {@code AdvisoryIngestService} produces from the NVD mirror. It is
 * what lets the report distinguish a legacy finding the replacement <em>drops</em>
 * (#3) from one it merely <em>downgrades</em> to {@code HEURISTIC} (#2, #5).
 */
final class SyntheticComparisonFixture {

    static final String LABEL =
            "SYNTHETIC fixture — 11 hand-built components, not customer data";

    private static final Instant PUBLISHED = Instant.parse("2024-01-15T00:00:00Z");

    private final RepositoryJpaRepository repositories;
    private final ComponentJpaRepository components;
    private final CveEntryJpaRepository cveEntries;
    private final CveAffectedProductJpaRepository cveAffected;
    private final AdvisoryJpaRepository advisories;
    private final AdvisoryAffectedJpaRepository advisoryAffected;

    private UUID repositoryId;

    SyntheticComparisonFixture(
            RepositoryJpaRepository repositories,
            ComponentJpaRepository components,
            CveEntryJpaRepository cveEntries,
            CveAffectedProductJpaRepository cveAffected,
            AdvisoryJpaRepository advisories,
            AdvisoryAffectedJpaRepository advisoryAffected) {
        this.repositories = repositories;
        this.components = components;
        this.cveEntries = cveEntries;
        this.cveAffected = cveAffected;
        this.advisories = advisories;
        this.advisoryAffected = advisoryAffected;
    }

    /** Number of components the fixture creates. */
    static final int COMPONENT_COUNT = 11;

    void clear() {
        advisoryAffected.deleteAllInBatch();
        advisories.deleteAllInBatch();
        cveAffected.deleteAllInBatch();
        cveEntries.deleteAllInBatch();
        components.deleteAllInBatch();
        repositories.deleteAllInBatch();
    }

    UUID repositoryId() {
        return repositoryId;
    }

    void load() {
        repositoryId = repository("synthetic-maven", "maven2");

        // ---- advisories, both stores -----------------------------------
        // 1/2: com.acme:util — the namespace collision.
        cve("CVE-2024-11001", 9.1, "CRITICAL", "Deserialisation flaw in the Acme utility library");
        cpeRange("CVE-2024-11001", "acme", "util", null, null, null, null, "2.0");
        advisory("CVE-2024-11001", "OSV", 9.1, "CRITICAL",
                "Deserialisation flaw in the Acme utility library");
        purlRange("CVE-2024-11001", "maven", "com.acme", "util", "<2.0", null, "2.0", null);
        cpeDerivedRange("CVE-2024-11001", "acme", "util", "cpe acme:util <2.0", null, "2.0", null);

        // 3/4: log4j — the sub-artifact folding.
        cve("CVE-2021-44228", 10.0, "CRITICAL", "Log4Shell: JNDI lookup in log4j-core");
        cpeRange("CVE-2021-44228", "apache", "log4j", null, "2.0", null, null, "2.15.0");
        advisory("CVE-2021-44228", "OSV", 10.0, "CRITICAL",
                "Log4Shell: JNDI lookup in log4j-core");
        purlRange("CVE-2021-44228", "maven", "org.apache.logging.log4j", "log4j-core",
                ">=2.0-beta9, <2.15.0", "2.0-beta9", "2.15.0", null);
        cpeDerivedRange("CVE-2021-44228", "apache", "log4j",
                "cpe apache:log4j >=2.0, <2.15.0", "2.0", "2.15.0", null);

        // 5: commons-text — one vulnerability, two upstream ids.
        cve("CVE-2022-42889", 9.8, "CRITICAL", "Text4Shell: variable interpolation RCE");
        cpeRange("CVE-2022-42889", "apache", "commons_text", null, "1.5", null, null, "1.10.0");
        advisory("CVE-2022-42889", "NVD", 9.8, "CRITICAL",
                "Text4Shell: variable interpolation RCE");
        cpeDerivedRange("CVE-2022-42889", "apache", "commons_text",
                "cpe apache:commons_text >=1.5, <1.10.0", "1.5", "1.10.0", null);
        advisory("GHSA-599f-7c49-w659", "GHSA", 9.8, "CRITICAL",
                "Apache Commons Text vulnerable to RCE through variable interpolation");
        purlRange("GHSA-599f-7c49-w659", "maven", "org.apache.commons", "commons-text",
                ">=1.5, <1.10.0", "1.5", "1.10.0", null);

        // 6: Maven service pack of the fixed release.
        cve("CVE-2024-11006", 7.5, "HIGH", "Zip bomb in the archive reader");
        cpeRange("CVE-2024-11006", "apache", "commons_compress", null, null, null, null, "1.21");
        advisory("CVE-2024-11006", "OSV", 7.5, "HIGH", "Zip bomb in the archive reader");
        purlRange("CVE-2024-11006", "maven", "org.apache.commons", "commons-compress",
                "<1.21", null, "1.21", null);
        cpeDerivedRange("CVE-2024-11006", "apache", "commons_compress",
                "cpe apache:commons_compress <1.21", null, "1.21", null);

        // 7: PEP 440 post release of the fixed version.
        cve("CVE-2024-11007", 8.1, "HIGH", "Heap overflow in the image decoder");
        cpeRange("CVE-2024-11007", "python", "pillow", null, null, null, null, "9.0.1");
        advisory("CVE-2024-11007", "OSV", 8.1, "HIGH", "Heap overflow in the image decoder");
        purlRange("CVE-2024-11007", "pypi", null, "pillow", "<9.0.1", null, "9.0.1", null);
        cpeDerivedRange("CVE-2024-11007", "python", "pillow",
                "cpe python:pillow <9.0.1", null, "9.0.1", null);

        // 8: the permissive direction — a vulnerable build the legacy ordering
        // sorts out of the range.
        cve("CVE-2024-11008", 6.5, "MEDIUM", "Path traversal in the widget renderer");
        cpeRange("CVE-2024-11008", "acme", "widget", null, "1.0-alpha9", null, null, "1.0-beta1");
        advisory("CVE-2024-11008", "OSV", 6.5, "MEDIUM", "Path traversal in the widget renderer");
        purlRange("CVE-2024-11008", "maven", "com.acme", "widget",
                ">=1.0-alpha9, <1.0-beta1", "1.0-alpha9", "1.0-beta1", null);
        cpeDerivedRange("CVE-2024-11008", "acme", "widget",
                "cpe acme:widget >=1.0-alpha9, <1.0-beta1", "1.0-alpha9", "1.0-beta1", null);

        // 10: a CPE product that matches a raw file's name.
        cve("CVE-2024-11010", 9.8, "CRITICAL", "OGNL injection in the Struts core");
        cpeRange("CVE-2024-11010", "apache", "struts2", null, null, null, null, null);
        advisory("CVE-2024-11010", "NVD", 9.8, "CRITICAL", "OGNL injection in the Struts core");
        cpeDerivedRange("CVE-2024-11010", "apache", "struts2", "cpe apache:struts2 *", null, null, null);

        // 11: purl-only, no CPE product reaches it.
        advisory("GHSA-orphan-0001", "GHSA", 7.2, "HIGH",
                "Authentication bypass in the Example orphan library");
        purlRange("GHSA-orphan-0001", "maven", "io.example", "orphan-lib", "<2.0", null, "2.0", null);

        // ---- components -------------------------------------------------
        component("maven2", "com.acme", "util", "1.0");
        component("maven2", "org.other", "util", "1.0");
        component("maven2", "org.apache.logging.log4j", "log4j-api", "2.14.1");
        component("maven2", "org.apache.logging.log4j", "log4j-core", "2.14.1");
        component("maven2", "org.apache.commons", "commons-text", "1.9");
        component("maven2", "org.apache.commons", "commons-compress", "1.21-sp1");
        component("pypi", null, "pillow", "9.0.1.post1");
        component("maven2", "com.acme", "widget", "1.0-alpha10");
        component("maven2", "com.acme", "safe", "3.0");
        component("raw", "vendor-drops", "struts2", "1");
        component("maven2", "io.example", "orphan-lib", "1.2.3");
    }

    // ---------------------------------------------------------------- rows

    private UUID repository(String name, String format) {
        RepositoryEntity entity = new RepositoryEntity();
        entity.setName(name);
        entity.setFormat(format);
        entity.setType("HOSTED");
        entity.setOnline(true);
        entity.setBlobStoreName("default");
        entity.setCreatedAt(PUBLISHED);
        entity.setUpdatedAt(PUBLISHED);
        return repositories.saveAndFlush(entity).getId();
    }

    private void component(String format, String namespace, String name, String version) {
        ComponentEntity entity = new ComponentEntity();
        entity.setRepositoryId(repositoryId);
        entity.setFormat(format);
        entity.setNamespace(namespace);
        entity.setName(name);
        entity.setVersion(version);
        entity.setCreatedAt(PUBLISHED);
        entity.setUpdatedAt(PUBLISHED);
        components.saveAndFlush(entity);
    }

    private void cve(String id, double score, String severity, String description) {
        CveEntryEntity entity = new CveEntryEntity();
        entity.setCveId(id);
        entity.setPublished(PUBLISHED);
        entity.setLastModified(PUBLISHED);
        entity.setCvssScore(score);
        entity.setCvssVersion("3.1");
        entity.setSeverity(severity);
        entity.setDescription(description);
        cveEntries.saveAndFlush(entity);
    }

    private void cpeRange(
            String cveId,
            String vendor,
            String product,
            String versionExact,
            String startIncluding,
            String startExcluding,
            String endIncluding,
            String endExcluding) {
        CveAffectedProductEntity entity = new CveAffectedProductEntity();
        entity.setCveId(cveId);
        entity.setVendor(vendor);
        entity.setProduct(product);
        entity.setVersionExact(versionExact);
        entity.setVersionStartIncluding(startIncluding);
        entity.setVersionStartExcluding(startExcluding);
        entity.setVersionEndIncluding(endIncluding);
        entity.setVersionEndExcluding(endExcluding);
        cveAffected.saveAndFlush(entity);
    }

    private void advisory(
            String id, String source, Double score, String severity, String summary) {
        AdvisoryEntity entity = new AdvisoryEntity();
        entity.setId(id);
        entity.setSource(source);
        entity.setSummary(summary);
        entity.setSeverity(severity);
        entity.setCvssScore(score);
        entity.setPublished(PUBLISHED);
        entity.setModified(PUBLISHED);
        advisories.saveAndFlush(entity);
    }

    private void purlRange(
            String advisoryId,
            String purlType,
            String namespace,
            String name,
            String versionRange,
            String introduced,
            String fixed,
            String lastAffected) {
        AdvisoryAffectedEntity entity = new AdvisoryAffectedEntity();
        entity.setAdvisoryId(advisoryId);
        entity.setPurlType(purlType);
        entity.setPurlNamespace(namespace);
        entity.setPurlName(name);
        entity.setVersionRange(versionRange);
        entity.setIntroduced(introduced);
        entity.setFixed(fixed);
        entity.setLastAffected(lastAffected);
        advisoryAffected.saveAndFlush(entity);
    }

    /** What {@code AdvisoryIngestService} writes for an NVD CPE match. */
    private void cpeDerivedRange(
            String advisoryId,
            String vendor,
            String product,
            String versionRange,
            String introduced,
            String fixed,
            String lastAffected) {
        purlRange(advisoryId, "cpe", vendor, product, versionRange, introduced, fixed, lastAffected);
    }
}
