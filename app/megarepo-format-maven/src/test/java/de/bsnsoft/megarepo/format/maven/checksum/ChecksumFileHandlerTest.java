package de.bsnsoft.megarepo.format.maven.checksum;

import de.bsnsoft.megarepo.core.format.FormatResponse;
import de.bsnsoft.megarepo.core.format.FormatResponse.ContentResponse;
import de.bsnsoft.megarepo.core.format.FormatResponse.NotFoundResponse;
import de.bsnsoft.megarepo.database.entity.AssetEntity;
import de.bsnsoft.megarepo.database.repository.AssetJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChecksumFileHandlerTest {

    @Mock
    private AssetJpaRepository assetRepository;

    private ChecksumFileHandler handler;

    private static final UUID REPO_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        handler = new ChecksumFileHandler(assetRepository);
    }

    @Test
    void sha1Request_returnsChecksum() throws IOException {
        AssetEntity asset = createAssetEntity("org/example/lib/1.0/lib-1.0.jar");
        asset.setChecksumSha1("da39a3ee5e6b4b0d3255bfef95601890afd80709");

        when(assetRepository.findByRepositoryIdAndPath(REPO_ID, "org/example/lib/1.0/lib-1.0.jar"))
                .thenReturn(Optional.of(asset));

        FormatResponse response = handler.handleChecksumRequest(REPO_ID, "org/example/lib/1.0/lib-1.0.jar.sha1");

        assertInstanceOf(ContentResponse.class, response);
        ContentResponse content = (ContentResponse) response;
        assertEquals("text/plain", content.contentType());

        String checksumValue = new String(content.content().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", checksumValue);
    }

    @Test
    void md5Request_returnsChecksum() throws IOException {
        AssetEntity asset = createAssetEntity("org/example/lib/1.0/lib-1.0.jar");
        asset.setChecksumMd5("d41d8cd98f00b204e9800998ecf8427e");

        when(assetRepository.findByRepositoryIdAndPath(REPO_ID, "org/example/lib/1.0/lib-1.0.jar"))
                .thenReturn(Optional.of(asset));

        FormatResponse response = handler.handleChecksumRequest(REPO_ID, "org/example/lib/1.0/lib-1.0.jar.md5");

        assertInstanceOf(ContentResponse.class, response);
        ContentResponse content = (ContentResponse) response;
        String checksumValue = new String(content.content().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", checksumValue);
    }

    @Test
    void sha256Request_returnsChecksum() throws IOException {
        AssetEntity asset = createAssetEntity("org/example/lib/1.0/lib-1.0.jar");
        asset.setChecksumSha256("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");

        when(assetRepository.findByRepositoryIdAndPath(REPO_ID, "org/example/lib/1.0/lib-1.0.jar"))
                .thenReturn(Optional.of(asset));

        FormatResponse response = handler.handleChecksumRequest(REPO_ID, "org/example/lib/1.0/lib-1.0.jar.sha256");

        assertInstanceOf(ContentResponse.class, response);
        ContentResponse content = (ContentResponse) response;
        String checksumValue = new String(content.content().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", checksumValue);
    }

    @Test
    void sha512Request_returnsChecksum() throws IOException {
        AssetEntity asset = createAssetEntity("org/example/lib/1.0/lib-1.0.jar");
        asset.setChecksumSha512("cf83e1357eefb8bdf1542850d66d8007d620e4050b5715dc83f4a921d36ce9ce");

        when(assetRepository.findByRepositoryIdAndPath(REPO_ID, "org/example/lib/1.0/lib-1.0.jar"))
                .thenReturn(Optional.of(asset));

        FormatResponse response = handler.handleChecksumRequest(REPO_ID, "org/example/lib/1.0/lib-1.0.jar.sha512");

        assertInstanceOf(ContentResponse.class, response);
        ContentResponse content = (ContentResponse) response;
        String checksumValue = new String(content.content().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals("cf83e1357eefb8bdf1542850d66d8007d620e4050b5715dc83f4a921d36ce9ce", checksumValue);
    }

    @Test
    void checksumForMissingAsset_returnsNotFound() {
        when(assetRepository.findByRepositoryIdAndPath(REPO_ID, "org/example/lib/1.0/lib-1.0.jar"))
                .thenReturn(Optional.empty());

        FormatResponse response = handler.handleChecksumRequest(REPO_ID, "org/example/lib/1.0/lib-1.0.jar.sha1");

        assertInstanceOf(NotFoundResponse.class, response);
    }

    @Test
    void checksumNotAvailable_returnsNotFound() {
        AssetEntity asset = createAssetEntity("org/example/lib/1.0/lib-1.0.jar");
        // All checksums are null by default

        when(assetRepository.findByRepositoryIdAndPath(REPO_ID, "org/example/lib/1.0/lib-1.0.jar"))
                .thenReturn(Optional.of(asset));

        FormatResponse response = handler.handleChecksumRequest(REPO_ID, "org/example/lib/1.0/lib-1.0.jar.sha1");

        assertInstanceOf(NotFoundResponse.class, response);
    }

    @Test
    void nonChecksumPath_returnsNotFound() {
        FormatResponse response = handler.handleChecksumRequest(REPO_ID, "org/example/lib/1.0/lib-1.0.jar");

        assertInstanceOf(NotFoundResponse.class, response);
    }

    @Test
    void isChecksumPath_trueForSha1() {
        assertTrue(handler.isChecksumPath("anything.sha1"));
    }

    @Test
    void isChecksumPath_trueForMd5() {
        assertTrue(handler.isChecksumPath("anything.md5"));
    }

    @Test
    void isChecksumPath_trueForSha256() {
        assertTrue(handler.isChecksumPath("anything.sha256"));
    }

    @Test
    void isChecksumPath_trueForSha512() {
        assertTrue(handler.isChecksumPath("anything.sha512"));
    }

    @Test
    void isChecksumPath_falseForJar() {
        assertFalse(handler.isChecksumPath("lib-1.0.jar"));
    }

    @Test
    void isChecksumPath_falseForPom() {
        assertFalse(handler.isChecksumPath("app-2.0.pom"));
    }

    @Test
    void isChecksumPath_falseForXml() {
        assertFalse(handler.isChecksumPath("maven-metadata.xml"));
    }

    @Test
    void checksumContentLength_matchesActualBytes() throws IOException {
        AssetEntity asset = createAssetEntity("org/example/lib/1.0/lib-1.0.jar");
        String sha1 = "da39a3ee5e6b4b0d3255bfef95601890afd80709";
        asset.setChecksumSha1(sha1);

        when(assetRepository.findByRepositoryIdAndPath(REPO_ID, "org/example/lib/1.0/lib-1.0.jar"))
                .thenReturn(Optional.of(asset));

        FormatResponse response = handler.handleChecksumRequest(REPO_ID, "org/example/lib/1.0/lib-1.0.jar.sha1");

        assertInstanceOf(ContentResponse.class, response);
        ContentResponse content = (ContentResponse) response;
        assertEquals(sha1.getBytes(StandardCharsets.UTF_8).length, content.contentLength());
    }

    private AssetEntity createAssetEntity(String path) {
        AssetEntity asset = new AssetEntity();
        asset.setId(UUID.randomUUID());
        asset.setRepositoryId(REPO_ID);
        asset.setPath(path);
        asset.setFormat("maven2");
        asset.setContentType("application/java-archive");
        asset.setSize(1024L);
        asset.setLastModified(Instant.now());
        asset.setCreatedAt(Instant.now());
        asset.setUpdatedAt(Instant.now());
        return asset;
    }
}
