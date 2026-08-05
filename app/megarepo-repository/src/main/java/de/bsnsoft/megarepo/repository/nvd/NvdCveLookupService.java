package de.bsnsoft.megarepo.repository.nvd;

import de.bsnsoft.megarepo.database.entity.ComponentEntity;
import de.bsnsoft.megarepo.database.entity.CveAffectedProductEntity;
import de.bsnsoft.megarepo.database.entity.CveEntryEntity;
import de.bsnsoft.megarepo.database.repository.CveAffectedProductJpaRepository;
import de.bsnsoft.megarepo.database.repository.CveEntryJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Maps artifact coordinates to CPE product candidates, queries the local CVE
 * mirror, and filters hits by version range using {@link VersionComparator}.
 *
 * CPE dictionary quirks handled:
 *  - product names use underscores where Maven uses dashes (commons-text → commons_text)
 *  - sub-artifacts fold into a base project name (log4j-core → log4j)
 *  - some products drop the vendor/group prefix, some keep it
 */
@Service
public class NvdCveLookupService {

    private static final Logger log = LoggerFactory.getLogger(NvdCveLookupService.class);

    private final CveEntryJpaRepository cveRepo;
    private final CveAffectedProductJpaRepository affectedRepo;

    public NvdCveLookupService(CveEntryJpaRepository cveRepo, CveAffectedProductJpaRepository affectedRepo) {
        this.cveRepo = cveRepo;
        this.affectedRepo = affectedRepo;
    }

    public record Hit(String cveId, double cvssScore, String severity, String description) {}

    public List<Hit> findApplicableCves(ComponentEntity component) {
        return findApplicableCves(component.getFormat(), component.getNamespace(),
                component.getName(), component.getVersion());
    }

    public List<Hit> findApplicableCves(String format, String namespace, String name, String version) {
        if (name == null || version == null) return List.of();

        Set<String> productCandidates = buildProductCandidates(name);
        if (productCandidates.isEmpty()) return List.of();

        List<CveAffectedProductEntity> matches = affectedRepo.findByProductIn(productCandidates);
        if (matches.isEmpty()) return List.of();

        // Filter by version applicability
        Set<String> applicableCveIds = new LinkedHashSet<>();
        for (var m : matches) {
            if (versionApplies(m, version)) {
                applicableCveIds.add(m.getCveId());
            }
        }
        if (applicableCveIds.isEmpty()) return List.of();

        Map<String, CveEntryEntity> byId = cveRepo.findAllById(applicableCveIds).stream()
                .collect(Collectors.toMap(CveEntryEntity::getCveId, e -> e));

        List<Hit> hits = new ArrayList<>(applicableCveIds.size());
        for (var id : applicableCveIds) {
            CveEntryEntity entry = byId.get(id);
            if (entry == null) continue;
            hits.add(new Hit(entry.getCveId(), entry.getCvssScore(), entry.getSeverity(), entry.getDescription()));
        }
        hits.sort(Comparator.comparingDouble(Hit::cvssScore).reversed());
        log.debug("Lookup {}:{}: {} candidate products → {} matches → {} applicable CVEs",
                namespace, name, productCandidates.size(), matches.size(), hits.size());
        return hits;
    }

    /**
     * Build the list of CPE product names to try. Order preserved: most specific first.
     * - as-is ("commons-text")
     * - dashes → underscores ("commons_text")
     * - dashes → dots ("log4j.core" — unusual but seen in some CPEs)
     * - first segment before dash ("log4j" from "log4j-core")
     * - all segments joined with underscore ("log4j_core")
     * - lowercased variants
     *
     * <p>Visible beyond this package so that the CPE/purl comparison report can
     * measure <em>this</em> candidate generation rather than a copy of it. The
     * behaviour is unchanged.
     */
    public static Set<String> buildProductCandidates(String artifactName) {
        Set<String> out = new LinkedHashSet<>();
        addVariants(out, artifactName);
        addVariants(out, artifactName.toLowerCase());

        if (artifactName.contains("-")) {
            addVariants(out, artifactName.replace('-', '_'));
            addVariants(out, artifactName.replace('-', '_').toLowerCase());

            int firstDash = artifactName.indexOf('-');
            addVariants(out, artifactName.substring(0, firstDash));
            addVariants(out, artifactName.substring(0, firstDash).toLowerCase());
        }

        // Drop very short candidates (would produce too many false positives on join)
        return out.stream().filter(s -> s.length() >= 2).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static void addVariants(Set<String> out, String s) {
        if (s == null || s.isBlank()) return;
        out.add(s);
    }

    /**
     * Whether a CPE match covers the given version, using the generic
     * {@link VersionComparator}.
     *
     * <p>Visible beyond this package for the same reason as
     * {@link #buildProductCandidates(String)}: the comparison report has to
     * apply the shipping predicate, not an equivalent one. Unchanged otherwise.
     */
    public static boolean versionApplies(CveAffectedProductEntity m, String version) {
        if (m.getVersionExact() != null) {
            return VersionComparator.compare(version, m.getVersionExact()) == 0;
        }

        boolean hasRange = m.getVersionStartIncluding() != null
                || m.getVersionStartExcluding() != null
                || m.getVersionEndIncluding() != null
                || m.getVersionEndExcluding() != null;

        if (!hasRange) {
            // CPE wildcarded version (":*:" in criteria) with no range — treat as
            // "all versions affected". Rare for real CVEs; we accept it.
            return true;
        }

        if (m.getVersionStartIncluding() != null
                && VersionComparator.compare(version, m.getVersionStartIncluding()) < 0) return false;
        if (m.getVersionStartExcluding() != null
                && VersionComparator.compare(version, m.getVersionStartExcluding()) <= 0) return false;
        if (m.getVersionEndIncluding() != null
                && VersionComparator.compare(version, m.getVersionEndIncluding()) > 0) return false;
        if (m.getVersionEndExcluding() != null
                && VersionComparator.compare(version, m.getVersionEndExcluding()) >= 0) return false;
        return true;
    }

    public Optional<CveEntryEntity> getById(String cveId) {
        return cveRepo.findById(cveId);
    }

    public long cveCount() {
        return cveRepo.count();
    }
}
