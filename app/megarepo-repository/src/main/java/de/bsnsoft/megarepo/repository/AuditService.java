package de.bsnsoft.megarepo.repository;

import de.bsnsoft.megarepo.database.entity.AuditLogEntity;
import de.bsnsoft.megarepo.database.repository.AuditLogJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditLogJpaRepository auditLogJpaRepository;

    public AuditService(AuditLogJpaRepository auditLogJpaRepository) {
        this.auditLogJpaRepository = auditLogJpaRepository;
    }

    @Async
    @Transactional
    public void logDownload(
            String user, String repo, String path, String format, long size, String ip, long durationMs) {
        saveEntry("DOWNLOAD", user, repo, path, null, format, size, ip, durationMs);
    }

    @Async
    @Transactional
    public void logUpload(String user, String repo, String path, String format, long size, String ip) {
        saveEntry("UPLOAD", user, repo, path, null, format, size, ip, null);
    }

    @Async
    @Transactional
    public void logDelete(String user, String repo, String path, String format, String ip) {
        saveEntry("DELETE", user, repo, path, null, format, null, ip, null);
    }

    @Async
    @Transactional
    public void logProxyFetch(
            String user,
            String repo,
            String path,
            String sourceUrl,
            String format,
            long size,
            String ip,
            long durationMs) {
        saveEntry("PROXY_FETCH", user, repo, path, sourceUrl, format, size, ip, durationMs);
    }

    @Async
    @Transactional
    public void logCacheHit(String user, String repo, String path, String format, String ip) {
        saveEntry("CACHE_HIT", user, repo, path, null, format, null, ip, null);
    }

    public Page<AuditLogEntity> findByRepository(String repository, Pageable pageable) {
        return auditLogJpaRepository.findByRepositoryOrderByTimestampDesc(repository, pageable);
    }

    public Page<AuditLogEntity> findByUser(String userId, Pageable pageable) {
        return auditLogJpaRepository.findByUserIdOrderByTimestampDesc(userId, pageable);
    }

    public Page<AuditLogEntity> findByTimeRange(Instant from, Instant to, Pageable pageable) {
        return auditLogJpaRepository.findByTimestampBetween(from, to, pageable);
    }

    public Page<AuditLogEntity> findByRepositoryAndTimeRange(
            String repository, Instant from, Instant to, Pageable pageable) {
        return auditLogJpaRepository.findByRepositoryAndTimestampBetween(repository, from, to, pageable);
    }

    public Page<AuditLogEntity> findByAction(String action, Pageable pageable) {
        return auditLogJpaRepository.findByAction(action, pageable);
    }

    public Page<AuditLogEntity> findByRepositoryAndAction(String repository, String action, Pageable pageable) {
        return auditLogJpaRepository.findByRepositoryAndAction(repository, action, pageable);
    }

    public Page<AuditLogEntity> findAll(Pageable pageable) {
        return auditLogJpaRepository.findAll(pageable);
    }

    private void saveEntry(
            String action,
            String user,
            String repo,
            String path,
            String sourceUrl,
            String format,
            Long size,
            String ip,
            Long durationMs) {
        try {
            var entry = new AuditLogEntity();
            entry.setTimestamp(Instant.now());
            entry.setUserId(user);
            entry.setAction(action);
            entry.setRepository(repo);
            entry.setPath(path);
            entry.setSourceUrl(sourceUrl);
            entry.setFormat(format);
            entry.setSize(size);
            entry.setIpAddress(ip);
            entry.setDurationMs(durationMs);
            auditLogJpaRepository.save(entry);
        } catch (Exception e) {
            log.error("Failed to save audit log entry: action={} repo={} path={}", action, repo, path, e);
        }
    }
}
