package de.bsnsoft.megarepo.format.maven.metadata;

import de.bsnsoft.megarepo.core.storage.BlobRef;
import de.bsnsoft.megarepo.core.storage.BlobStore;
import de.bsnsoft.megarepo.database.entity.AssetEntity;
import de.bsnsoft.megarepo.database.entity.ComponentEntity;
import de.bsnsoft.megarepo.database.repository.AssetJpaRepository;
import de.bsnsoft.megarepo.database.repository.ComponentJpaRepository;
import de.bsnsoft.megarepo.storage.BlobStoreManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MavenMetadataGeneratorTest {

    @Mock
    private ComponentJpaRepository componentJpaRepository;

    @Mock
    private AssetJpaRepository assetJpaRepository;

    @Mock
    private BlobStoreManager blobStoreManager;

    @Mock
    private BlobStore blobStore;

    private MavenMetadataGenerator generator;

    private static final UUID REPO_ID = UUID.randomUUID();
    private static final String BLOB_STORE_NAME = "default";

    @BeforeEach
    void setUp() {
        generator = new MavenMetadataGenerator(componentJpaRepository, assetJpaRepository, blobStoreManager);
    }

    @Test
    void generateMetadata_threeVersions_correctXmlWithSortedVersionsAndLatestAndRelease() {
        var components = List.of(
                createComponent("com.example", "my-lib", "1.0"),
                createComponent("com.example", "my-lib", "1.2"),
                createComponent("com.example", "my-lib", "1.1"));

        when(componentJpaRepository.findByRepositoryIdAndNamespaceAndName(REPO_ID, "com.example", "my-lib"))
                .thenReturn(components);
        when(blobStoreManager.get(BLOB_STORE_NAME)).thenReturn(blobStore);
        when(blobStore.store(any(InputStream.class), anyLong(), any()))
                .thenReturn(new BlobRef(BLOB_STORE_NAME, "blob-1"));
        when(assetJpaRepository.findByRepositoryIdAndPath(any(), any())).thenReturn(Optional.empty());
        when(assetJpaRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        String xml = generator.generateMetadata(REPO_ID, BLOB_STORE_NAME, "com.example", "my-lib");

        assertNotNull(xml);
        assertTrue(xml.contains("<groupId>com.example</groupId>"));
        assertTrue(xml.contains("<artifactId>my-lib</artifactId>"));
        assertTrue(xml.contains("<latest>1.2</latest>"));
        assertTrue(xml.contains("<release>1.2</release>"));

        // Verify versions are sorted
        int idx10 = xml.indexOf("<version>1.0</version>");
        int idx11 = xml.indexOf("<version>1.1</version>");
        int idx12 = xml.indexOf("<version>1.2</version>");
        assertTrue(idx10 < idx11, "1.0 should come before 1.1");
        assertTrue(idx11 < idx12, "1.1 should come before 1.2");

        // Verify asset was stored
        verify(assetJpaRepository).save(any(AssetEntity.class));
    }

    @Test
    void generateMetadata_onlySnapshots_releaseIsAbsent() {
        var components = List.of(
                createComponent("org.test", "snap-lib", "1.0-SNAPSHOT"),
                createComponent("org.test", "snap-lib", "2.0-SNAPSHOT"));

        when(componentJpaRepository.findByRepositoryIdAndNamespaceAndName(REPO_ID, "org.test", "snap-lib"))
                .thenReturn(components);
        when(blobStoreManager.get(BLOB_STORE_NAME)).thenReturn(blobStore);
        when(blobStore.store(any(InputStream.class), anyLong(), any()))
                .thenReturn(new BlobRef(BLOB_STORE_NAME, "blob-2"));
        when(assetJpaRepository.findByRepositoryIdAndPath(any(), any())).thenReturn(Optional.empty());
        when(assetJpaRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        String xml = generator.generateMetadata(REPO_ID, BLOB_STORE_NAME, "org.test", "snap-lib");

        assertTrue(xml.contains("<latest>2.0-SNAPSHOT</latest>"));
        assertFalse(xml.contains("<release>"), "No release element when all versions are snapshots");
    }

    @Test
    void serializeAndParse_roundTrip_producesEquivalentModel() {
        var model = new MavenMetadataModel(
                "com.example",
                "my-lib",
                null,
                new MavenMetadataModel.Versioning(
                        "2.0",
                        "2.0",
                        List.of("1.0", "1.5", "2.0"),
                        "20260328120000",
                        null,
                        null));

        String xml = generator.serializeToXml(model);
        MavenMetadataModel parsed = generator.parseFromXml(xml);

        assertEquals(model.groupId(), parsed.groupId());
        assertEquals(model.artifactId(), parsed.artifactId());
        assertEquals(model.version(), parsed.version());
        assertNotNull(parsed.versioning());
        assertEquals(model.versioning().latest(), parsed.versioning().latest());
        assertEquals(model.versioning().release(), parsed.versioning().release());
        assertEquals(model.versioning().versions(), parsed.versioning().versions());
        assertEquals(model.versioning().lastUpdated(), parsed.versioning().lastUpdated());
    }

    @Test
    void serializeToXml_lastUpdated_hasCorrectFormat() {
        var model = new MavenMetadataModel(
                "com.example",
                "my-lib",
                null,
                new MavenMetadataModel.Versioning("1.0", "1.0", List.of("1.0"), "20260328153045", null, null));

        String xml = generator.serializeToXml(model);

        assertTrue(xml.contains("<lastUpdated>20260328153045</lastUpdated>"));
        // Verify the format matches yyyyMMddHHmmss (14 digits)
        assertTrue(xml.matches("(?s).*<lastUpdated>\\d{14}</lastUpdated>.*"));
    }

    @Test
    void generateMetadata_storesAssetAtCorrectPath() {
        var components = List.of(createComponent("com.example.deep", "artifact", "1.0"));

        when(componentJpaRepository.findByRepositoryIdAndNamespaceAndName(REPO_ID, "com.example.deep", "artifact"))
                .thenReturn(components);
        when(blobStoreManager.get(BLOB_STORE_NAME)).thenReturn(blobStore);
        when(blobStore.store(any(InputStream.class), anyLong(), any()))
                .thenReturn(new BlobRef(BLOB_STORE_NAME, "blob-3"));
        when(assetJpaRepository.findByRepositoryIdAndPath(any(), any())).thenReturn(Optional.empty());
        when(assetJpaRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        generator.generateMetadata(REPO_ID, BLOB_STORE_NAME, "com.example.deep", "artifact");

        // Verify the asset is stored at the correct Maven path
        verify(assetJpaRepository)
                .findByRepositoryIdAndPath(REPO_ID, "com/example/deep/artifact/maven-metadata.xml");
    }

    @Test
    void parseFromXml_withSnapshotInfo_parsesCorrectly() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <metadata>
                  <groupId>com.example</groupId>
                  <artifactId>my-lib</artifactId>
                  <version>1.0-SNAPSHOT</version>
                  <versioning>
                    <snapshot>
                      <timestamp>20260328.120000</timestamp>
                      <buildNumber>3</buildNumber>
                    </snapshot>
                    <snapshotVersions>
                      <snapshotVersion>
                        <extension>jar</extension>
                        <value>1.0-20260328.120000-3</value>
                        <updated>20260328120000</updated>
                      </snapshotVersion>
                    </snapshotVersions>
                    <lastUpdated>20260328120000</lastUpdated>
                  </versioning>
                </metadata>
                """;

        MavenMetadataModel model = generator.parseFromXml(xml);

        assertEquals("com.example", model.groupId());
        assertEquals("my-lib", model.artifactId());
        assertEquals("1.0-SNAPSHOT", model.version());
        assertNotNull(model.versioning().snapshot());
        assertEquals("20260328.120000", model.versioning().snapshot().timestamp());
        assertEquals(3, model.versioning().snapshot().buildNumber());
        assertNotNull(model.versioning().snapshotVersions());
        assertEquals(1, model.versioning().snapshotVersions().size());
        assertEquals("jar", model.versioning().snapshotVersions().getFirst().extension());
    }

    @Test
    void generateSnapshotMetadata_nonSnapshotVersion_throwsException() {
        assertThrows(
                IllegalArgumentException.class,
                () -> generator.generateSnapshotMetadata(REPO_ID, BLOB_STORE_NAME, "com.example", "lib", "1.0"));
    }

    @Test
    void generateSnapshotMetadata_snapshotVersion_producesValidXml() {
        var component = createComponent("com.example", "snap-lib", "1.0-SNAPSHOT");
        component.setId(UUID.randomUUID());

        var asset = new AssetEntity();
        asset.setPath("com/example/snap-lib/1.0-SNAPSHOT/snap-lib-1.0-SNAPSHOT.jar");
        asset.setFormat("maven2");

        when(componentJpaRepository.findByRepositoryIdAndNamespaceAndNameAndVersion(
                        REPO_ID, "com.example", "snap-lib", "1.0-SNAPSHOT"))
                .thenReturn(Optional.of(component));
        when(assetJpaRepository.findByComponentId(any(), any()))
                .thenReturn(new PageImpl<>(List.of(asset)));
        when(blobStoreManager.get(BLOB_STORE_NAME)).thenReturn(blobStore);
        when(blobStore.store(any(InputStream.class), anyLong(), any()))
                .thenReturn(new BlobRef(BLOB_STORE_NAME, "blob-snap"));
        when(assetJpaRepository.findByRepositoryIdAndPath(any(), any())).thenReturn(Optional.empty());
        when(assetJpaRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        String xml = generator.generateSnapshotMetadata(REPO_ID, BLOB_STORE_NAME, "com.example", "snap-lib", "1.0-SNAPSHOT");

        assertTrue(xml.contains("<version>1.0-SNAPSHOT</version>"));
        assertTrue(xml.contains("<snapshot>"));
        assertTrue(xml.contains("<buildNumber>"));
        assertTrue(xml.contains("<timestamp>"));
    }

    private ComponentEntity createComponent(String namespace, String name, String version) {
        var entity = new ComponentEntity();
        entity.setId(UUID.randomUUID());
        entity.setRepositoryId(REPO_ID);
        entity.setFormat("maven2");
        entity.setNamespace(namespace);
        entity.setName(name);
        entity.setVersion(version);
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        return entity;
    }
}
