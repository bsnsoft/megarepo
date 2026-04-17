package de.bsnsoft.megarepo.repository.nvd;

import de.bsnsoft.megarepo.database.entity.CveAffectedProductEntity;
import de.bsnsoft.megarepo.database.entity.CveEntryEntity;
import de.bsnsoft.megarepo.database.entity.NvdSyncStateEntity;
import de.bsnsoft.megarepo.database.repository.CveAffectedProductJpaRepository;
import de.bsnsoft.megarepo.database.repository.CveEntryJpaRepository;
import de.bsnsoft.megarepo.database.repository.NvdSyncStateJpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Per-page persistence helpers extracted from {@link NvdSyncService} so that
 * {@link Transactional} boundaries are triggered via the Spring proxy (not
 * self-calls within the sync loop).
 */
@Service
public class NvdIngestService {

    private final CveEntryJpaRepository cveRepo;
    private final CveAffectedProductJpaRepository affectedRepo;
    private final NvdSyncStateJpaRepository stateRepo;

    public NvdIngestService(
            CveEntryJpaRepository cveRepo,
            CveAffectedProductJpaRepository affectedRepo,
            NvdSyncStateJpaRepository stateRepo) {
        this.cveRepo = cveRepo;
        this.affectedRepo = affectedRepo;
        this.stateRepo = stateRepo;
    }

    @Transactional
    public int ingestPage(List<NvdApiClient.CveData> cves) {
        if (cves.isEmpty()) return 0;
        List<String> cveIds = cves.stream().map(NvdApiClient.CveData::cveId).toList();
        affectedRepo.deleteByCveIdIn(cveIds);
        affectedRepo.flush();

        List<CveEntryEntity> entries = new ArrayList<>(cves.size());
        List<CveAffectedProductEntity> affected = new ArrayList<>();
        for (var c : cves) {
            var e = cveRepo.findById(c.cveId()).orElseGet(() -> {
                var n = new CveEntryEntity();
                n.setCveId(c.cveId());
                return n;
            });
            e.setPublished(c.published());
            e.setLastModified(c.lastModified());
            e.setCvssScore(c.cvssScore());
            e.setCvssVersion(c.cvssVersion());
            e.setSeverity(c.severity());
            e.setDescription(c.description());
            entries.add(e);

            for (var m : c.cpeMatches()) {
                var a = new CveAffectedProductEntity();
                a.setCveId(c.cveId());
                a.setVendor(m.vendor());
                a.setProduct(m.product());
                a.setVersionExact(m.versionExact());
                a.setVersionStartIncluding(m.versionStartIncluding());
                a.setVersionStartExcluding(m.versionStartExcluding());
                a.setVersionEndIncluding(m.versionEndIncluding());
                a.setVersionEndExcluding(m.versionEndExcluding());
                affected.add(a);
            }
        }
        cveRepo.saveAll(entries);
        affectedRepo.saveAll(affected);
        return cves.size();
    }

    @Transactional
    public void markStart(String mode) {
        var s = getOrCreateState();
        s.setStatus("SYNCING");
        s.setMode(mode);
        s.setStartedAt(Instant.now());
        s.setErrorMessage(null);
        s.setSyncedCves(0);
        s.setTotalResults(null);
        stateRepo.save(s);
    }

    @Transactional
    public void updateProgress(Integer totalResults, int synced) {
        var s = getOrCreateState();
        if (totalResults != null) s.setTotalResults(totalResults);
        s.setSyncedCves(synced);
        stateRepo.save(s);
    }

    @Transactional
    public void markSuccess() {
        var s = getOrCreateState();
        s.setStatus("IDLE");
        Instant now = Instant.now();
        s.setLastSyncAt(now);
        s.setLastSuccessAt(now);
        s.setTotalCves((int) cveRepo.count());
        s.setErrorMessage(null);
        stateRepo.save(s);
    }

    @Transactional
    public void markError(String message) {
        var s = getOrCreateState();
        s.setStatus("ERROR");
        s.setLastSyncAt(Instant.now());
        s.setErrorMessage(message);
        s.setTotalCves((int) cveRepo.count());
        stateRepo.save(s);
    }

    private NvdSyncStateEntity getOrCreateState() {
        return stateRepo.findById(1).orElseGet(() -> {
            var n = new NvdSyncStateEntity();
            return stateRepo.save(n);
        });
    }
}
