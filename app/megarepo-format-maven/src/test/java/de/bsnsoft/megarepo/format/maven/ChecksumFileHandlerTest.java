package de.bsnsoft.megarepo.format.maven;

import de.bsnsoft.megarepo.core.format.FormatResponse;
import de.bsnsoft.megarepo.core.format.FormatResponse.ContentResponse;
import de.bsnsoft.megarepo.core.format.FormatResponse.NotFoundResponse;
import de.bsnsoft.megarepo.database.entity.AssetEntity;
import de.bsnsoft.megarepo.database.repository.AssetJpaRepository;
import de.bsnsoft.megarepo.format.maven.checksum.ChecksumFileHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
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
    void handleChecksumRequest_sha1_returnsCorrectChecksum() throws IOException {
        AssetEntity asset = createAssetWithChecksums();
        when(assetRepository.findByRepositoryIdAndPath(REPO_ID, "org/example/foo/1.0/foo-1.0.jar"))
                .thenReturn(Optional.of(asset));

        FormatResponse response = handler.handleChecksumRequest(REPO_ID, "org/example/foo/1.0/foo-1.0.jar.sha1");

        assertInstanceOf(ContentResponse.class, response);
        ContentResponse content = (ContentResponse) response;
        assertEquals("text/plain", content.contentType());
        String body = new String(content.content().readAllBytes());
        assertEquals("abc123sha1", body);
    }

    @Test
    void handleChecksumRequest_md5_returnsCorrectChecksum() throws IOException {
        AssetEntity asset = createAssetWithChecksums();
        when(assetRepository.findByRepositoryIdAndPath(REPO_ID, "org/example/foo/1.0/foo-1.0.jar"))
                .thenReturn(Optional.of(asset));

        FormatResponse response = handler.handleChecksumRequest(REPO_ID, "org/example/foo/1.0/foo-1.0.jar.md5");

        assertInstanceOf(ContentResponse.class, response);
        ContentResponse content = (ContentResponse) response;
        String body = new String(content.content().readAllBytes());
        assertEquals("abc123md5", body);
    }

    @Test
    void handleChecksumRequest_unknownOriginalAsset_returns404() {
        when(assetRepository.findByRepositoryIdAndPath(REPO_ID, "org/example/foo/1.0/foo-1.0.jar"))
                .thenReturn(Optional.empty());

        FormatResponse response = handler.handleChecksumRequest(REPO_ID, "org/example/foo/1.0/foo-1.0.jar.sha1");

        assertInstanceOf(NotFoundResponse.class, response);
    }

    @Test
    void isChecksumPath_variousExtensions() {
        assertTrue(handler.isChecksumPath("file.jar.md5"));
        assertTrue(handler.isChecksumPath("file.jar.sha1"));
        assertTrue(handler.isChecksumPath("file.jar.sha256"));
        assertTrue(handler.isChecksumPath("file.jar.sha512"));
        assertFalse(handler.isChecksumPath("file.jar"));
        assertFalse(handler.isChecksumPath("file.jar.asc"));
        assertFalse(handler.isChecksumPath("file.pom"));
    }

    private AssetEntity createAssetWithChecksums() {
        AssetEntity asset = new AssetEntity();
        asset.setId(UUID.randomUUID());
        asset.setRepositoryId(REPO_ID);
        asset.setPath("org/example/foo/1.0/foo-1.0.jar");
        asset.setFormat("maven2");
        asset.setBlobRef("default@blob-123");
        asset.setContentType("application/java-archive");
        asset.setSize(1024L);
        asset.setChecksumMd5("abc123md5");
        asset.setChecksumSha1("abc123sha1");
        asset.setChecksumSha256("abc123sha256");
        asset.setChecksumSha512("abc123sha512");
        asset.setLastModified(Instant.now());
        asset.setCreatedAt(Instant.now());
        asset.setUpdatedAt(Instant.now());
        return asset;
    }
}
