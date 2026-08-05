package de.bsnsoft.megarepo.repository.advisory.nvd;

import de.bsnsoft.megarepo.database.entity.CveAffectedProductEntity;
import de.bsnsoft.megarepo.database.entity.CveEntryEntity;
import de.bsnsoft.megarepo.database.repository.CveAffectedProductJpaRepository;
import de.bsnsoft.megarepo.database.repository.CveEntryJpaRepository;
import de.bsnsoft.megarepo.repository.advisory.AdvisorySource;
import de.bsnsoft.megarepo.repository.advisory.AdvisorySyncException;
import de.bsnsoft.megarepo.repository.advisory.AdvisorySyncResult;
import de.bsnsoft.megarepo.repository.advisory.CpePurlTranslator;
import de.bsnsoft.megarepo.repository.advisory.NormalizedAdvisory;
import de.bsnsoft.megarepo.repository.advisory.NormalizedAffected;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Feeds NVD data into the normalised advisory store by reading MegaRepo's
 * existing NVD mirror.
 *
 * <h2>No new downloads</h2>
 *
 * The V8 NVD sync ({@code NvdSyncService} → {@code cve_entries} /
 * {@code cve_affected_products}) keeps running unchanged and stays the only
 * component that talks to nvd.nist.gov. This source reads what that mirror
 * already holds and normalises it into {@link NormalizedAdvisory}. Two
 * consequences worth knowing:
 *
 * <ul>
 *   <li>This source performs <b>no network I/O at all</b>, so it cannot fail
 *       from rate limits or upstream outages — only from the local database
 *       being unreadable, which it reports as
 *       {@link AdvisorySyncException} like any other source failure.</li>
 *   <li>Its freshness is the mirror's freshness. An advisory reaches the
 *       firewall one NVD sync plus one advisory sync after publication.</li>
 * </ul>
 *
 * <h2>What is lost on the way in</h2>
 *
 * The hard part is not paging, it is that NVD speaks CPE and the firewall
 * speaks purl. {@link CpePurlTranslator} documents in full why no lossless
 * mapping exists; the short version is that a CPE names no ecosystem, its
 * vendor is an organisation rather than a package namespace, and its product is
 * coarser than an artifact. Rather than guess the missing parts — the very
 * defect the customer reported — every affected range from this source is
 * stored under the reserved purl type {@code cpe} and every finding it produces
 * is labelled {@code HEURISTIC}.
 *
 * <p>Two further losses come from the mirror's own schema rather than from CPE:
 * it keeps neither the raw {@code cpe:2.3:…} URI (so the CPE {@code part} and
 * {@code target_sw} fields, which would at least hint at the ecosystem, are
 * gone before this class sees the data) nor a CVSS vector string. Both are
 * reported as absent instead of reconstructed.
 *
 * <h2>Cursor</h2>
 *
 * {@code <epochMilli>|<cveId>} — a keyset over
 * {@code (last_modified, cve_id)}. An unparseable cursor restarts from the
 * beginning with a warning rather than failing the source: the ingest is
 * idempotent, so a full re-read is merely slower, whereas a permanent error
 * state would need an operator to clear it.
 */
@Service
public class NvdAdvisorySource implements AdvisorySource {

    /** Value written to {@code advisory.source} and {@code advisory_sync_state.source}. */
    public static final String SOURCE_ID = "NVD";

    /**
     * CVEs read per {@link #sync(String)} call. The whole mirror is roughly
     * 300k CVEs with several million CPE matches; a batch keeps one ingest
     * transaction and its memory bounded.
     */
    static final int DEFAULT_BATCH_SIZE = 500;

    private static final Logger log = LoggerFactory.getLogger(NvdAdvisorySource.class);

    private final CveEntryJpaRepository cveRepository;
    private final CveAffectedProductJpaRepository cpeRepository;
    private final int batchSize;

    @Autowired
    public NvdAdvisorySource(
            CveEntryJpaRepository cveRepository, CveAffectedProductJpaRepository cpeRepository) {
        this(cveRepository, cpeRepository, DEFAULT_BATCH_SIZE);
    }

    /** Visible for tests, which need a batch size they can exhaust. */
    NvdAdvisorySource(
            CveEntryJpaRepository cveRepository,
            CveAffectedProductJpaRepository cpeRepository,
            int batchSize) {
        this.cveRepository = cveRepository;
        this.cpeRepository = cpeRepository;
        this.batchSize = batchSize;
    }

    @Override
    public String sourceId() {
        return SOURCE_ID;
    }

    @Override
    public AdvisorySyncResult sync(String cursor) throws AdvisorySyncException {
        Cursor position = Cursor.parse(cursor);

        List<CveEntryEntity> page;
        Map<String, List<CveAffectedProductEntity>> matchesByCve;
        try {
            page = cveRepository.findModifiedAfter(
                    position.lastModified(), position.cveId(), PageRequest.of(0, batchSize));
            if (page.isEmpty()) {
                return AdvisorySyncResult.empty();
            }
            List<String> cveIds = page.stream().map(CveEntryEntity::getCveId).toList();
            matchesByCve = cpeRepository.findByCveIdIn(cveIds).stream()
                    .collect(Collectors.groupingBy(CveAffectedProductEntity::getCveId));
        } catch (DataAccessException e) {
            // The local mirror is this source's "upstream". Reporting it the same
            // way a network failure is reported keeps the sync-state contract
            // uniform across sources.
            throw new AdvisorySyncException(
                    "Local NVD mirror is not readable (cve_entries/cve_affected_products)", e);
        }

        List<NormalizedAdvisory> advisories = new ArrayList<>(page.size());
        for (CveEntryEntity cve : page) {
            advisories.add(normalize(cve, matchesByCve.getOrDefault(cve.getCveId(), List.of())));
        }

        CveEntryEntity last = page.get(page.size() - 1);
        String nextCursor = new Cursor(last.getLastModified(), last.getCveId()).encode();
        boolean complete = page.size() < batchSize;

        log.debug("NVD advisory source: normalised {} CVEs, next cursor {}, complete={}",
                advisories.size(), nextCursor, complete);
        return new AdvisorySyncResult(advisories, nextCursor, complete);
    }

    private static NormalizedAdvisory normalize(
            CveEntryEntity cve, List<CveAffectedProductEntity> cpeMatches) {

        // De-duplicate before translating: NVD publishes one cpe_match per
        // configuration node, so the same vendor/product/range recurs for every
        // OS or platform combination it applies to. Once translated the platform
        // distinction is gone anyway, so keeping them would multiply
        // advisory_affected rows without adding information.
        Set<NormalizedAffected> affected = new LinkedHashSet<>();
        for (CveAffectedProductEntity match : cpeMatches) {
            CpePurlTranslator.translate(toCpeMatch(match)).ifPresent(affected::add);
        }

        return new NormalizedAdvisory(
                cve.getCveId(),
                SOURCE_ID,
                cve.getDescription(),
                cve.getSeverity(),
                scoreOf(cve),
                // The V8 mirror stores cvss_version but not the vector string.
                // Absent is the honest answer; a vector cannot be reconstructed
                // from a base score.
                null,
                cve.getPublished(),
                cve.getLastModified(),
                // NVD marks retracted entries by rewriting the description
                // ("** REJECT **") rather than with a timestamp, and the mirror
                // does not keep that marker in a queryable form. Withdrawal is
                // therefore left to the sources that publish it explicitly.
                null,
                List.copyOf(affected));
    }

    /**
     * CVSS score, or null when the mirror has none.
     *
     * <p>{@code cve_entries.cvss_score} is a NOT NULL column defaulting to 0, so
     * "no score published" and "scored 0.0" are stored identically — the exact
     * ambiguity V12 avoided by making {@code advisory.cvss_score} nullable.
     * Zero is mapped to null: a genuine CVSS 0.0 means "no impact" and is not
     * something a policy would act on, whereas a defaulted 0.0 presented as a
     * real score would tell an operator the vulnerability is harmless.
     */
    private static Double scoreOf(CveEntryEntity cve) {
        return cve.getCvssScore() == 0.0 ? null : cve.getCvssScore();
    }

    private static CpePurlTranslator.CpeMatch toCpeMatch(CveAffectedProductEntity entity) {
        return new CpePurlTranslator.CpeMatch(
                entity.getVendor(),
                entity.getProduct(),
                entity.getVersionExact(),
                entity.getVersionStartIncluding(),
                entity.getVersionStartExcluding(),
                entity.getVersionEndIncluding(),
                entity.getVersionEndExcluding());
    }

    /**
     * Keyset position in {@code cve_entries}, encoded as
     * {@code <epochMilli>|<cveId>}.
     */
    record Cursor(Instant lastModified, String cveId) {

        private static final Cursor BEGINNING = new Cursor(Instant.EPOCH, "");

        static Cursor parse(String encoded) {
            if (encoded == null || encoded.isBlank()) {
                return BEGINNING;
            }
            int separator = encoded.indexOf('|');
            if (separator < 0) {
                log.warn("Ignoring malformed NVD advisory cursor '{}' — restarting from the beginning",
                        encoded);
                return BEGINNING;
            }
            try {
                long epochMilli = Long.parseLong(encoded.substring(0, separator));
                return new Cursor(Instant.ofEpochMilli(epochMilli), encoded.substring(separator + 1));
            } catch (NumberFormatException e) {
                log.warn("Ignoring malformed NVD advisory cursor '{}' — restarting from the beginning",
                        encoded);
                return BEGINNING;
            }
        }

        String encode() {
            return lastModified.toEpochMilli() + "|" + cveId;
        }
    }
}
