package de.bsnsoft.megarepo.repository;

import de.bsnsoft.megarepo.database.entity.AuditLogEntity;
import de.bsnsoft.megarepo.database.repository.AuditLogJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AuditServiceTest {

    @Mock
    private AuditLogJpaRepository auditLogJpaRepository;

    private AuditService auditService;

    @BeforeEach
    void setUp() {
        auditService = new AuditService(auditLogJpaRepository);
    }

    @Test
    void logDownload_createsCorrectEntity() {
        auditService.logDownload("admin", "maven-releases", "com/example/lib-1.0.jar", "maven2", 1024L, "192.168.1.1", 50L);

        var captor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogJpaRepository).save(captor.capture());

        AuditLogEntity saved = captor.getValue();
        assertNotNull(saved.getTimestamp());
        assertEquals("admin", saved.getUserId());
        assertEquals("DOWNLOAD", saved.getAction());
        assertEquals("maven-releases", saved.getRepository());
        assertEquals("com/example/lib-1.0.jar", saved.getPath());
        assertEquals("maven2", saved.getFormat());
        assertEquals(1024L, saved.getSize());
        assertEquals("192.168.1.1", saved.getIpAddress());
        assertEquals(50L, saved.getDurationMs());
        assertNull(saved.getSourceUrl());
    }

    @Test
    void logUpload_createsCorrectEntity() {
        auditService.logUpload("deployer", "maven-releases", "com/example/lib-2.0.jar", "maven2", 2048L, "10.0.0.5");

        var captor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogJpaRepository).save(captor.capture());

        AuditLogEntity saved = captor.getValue();
        assertNotNull(saved.getTimestamp());
        assertEquals("deployer", saved.getUserId());
        assertEquals("UPLOAD", saved.getAction());
        assertEquals("maven-releases", saved.getRepository());
        assertEquals("com/example/lib-2.0.jar", saved.getPath());
        assertEquals("maven2", saved.getFormat());
        assertEquals(2048L, saved.getSize());
        assertEquals("10.0.0.5", saved.getIpAddress());
        assertNull(saved.getDurationMs());
        assertNull(saved.getSourceUrl());
    }

    @Test
    void logDelete_createsCorrectEntity() {
        auditService.logDelete("admin", "maven-releases", "com/example/old-1.0.jar", "maven2", "192.168.1.1");

        var captor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogJpaRepository).save(captor.capture());

        AuditLogEntity saved = captor.getValue();
        assertNotNull(saved.getTimestamp());
        assertEquals("admin", saved.getUserId());
        assertEquals("DELETE", saved.getAction());
        assertEquals("maven-releases", saved.getRepository());
        assertEquals("com/example/old-1.0.jar", saved.getPath());
        assertEquals("maven2", saved.getFormat());
        assertNull(saved.getSize());
        assertEquals("192.168.1.1", saved.getIpAddress());
        assertNull(saved.getDurationMs());
    }

    @Test
    void logProxyFetch_createsCorrectEntity() {
        auditService.logProxyFetch(
                "anonymous",
                "maven-central",
                "org/apache/commons/commons-lang3/3.12.0/commons-lang3-3.12.0.jar",
                "https://repo1.maven.org/maven2/org/apache/commons/commons-lang3/3.12.0/commons-lang3-3.12.0.jar",
                "maven2",
                512000L,
                "172.16.0.1",
                320L);

        var captor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogJpaRepository).save(captor.capture());

        AuditLogEntity saved = captor.getValue();
        assertNotNull(saved.getTimestamp());
        assertEquals("anonymous", saved.getUserId());
        assertEquals("PROXY_FETCH", saved.getAction());
        assertEquals("maven-central", saved.getRepository());
        assertEquals("org/apache/commons/commons-lang3/3.12.0/commons-lang3-3.12.0.jar", saved.getPath());
        assertEquals(
                "https://repo1.maven.org/maven2/org/apache/commons/commons-lang3/3.12.0/commons-lang3-3.12.0.jar",
                saved.getSourceUrl());
        assertEquals("maven2", saved.getFormat());
        assertEquals(512000L, saved.getSize());
        assertEquals("172.16.0.1", saved.getIpAddress());
        assertEquals(320L, saved.getDurationMs());
    }

    @Test
    void logCacheHit_createsCorrectEntity() {
        auditService.logCacheHit("admin", "maven-central", "org/example/lib-1.0.pom", "maven2", "10.0.0.1");

        var captor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogJpaRepository).save(captor.capture());

        AuditLogEntity saved = captor.getValue();
        assertNotNull(saved.getTimestamp());
        assertEquals("admin", saved.getUserId());
        assertEquals("CACHE_HIT", saved.getAction());
        assertEquals("maven-central", saved.getRepository());
        assertEquals("org/example/lib-1.0.pom", saved.getPath());
        assertEquals("maven2", saved.getFormat());
        assertNull(saved.getSize());
        assertEquals("10.0.0.1", saved.getIpAddress());
        assertNull(saved.getDurationMs());
        assertNull(saved.getSourceUrl());
    }
}
