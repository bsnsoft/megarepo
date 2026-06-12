package de.bsnsoft.megarepo.format.raw;

import de.bsnsoft.megarepo.core.format.ComponentUpload;
import de.bsnsoft.megarepo.core.format.ComponentUpload.UploadFile;
import de.bsnsoft.megarepo.core.format.FormatResponse.CreatedResponse;
import de.bsnsoft.megarepo.core.format.FormatResponse.ErrorResponse;
import de.bsnsoft.megarepo.core.repository.RepositoryConfig;
import de.bsnsoft.megarepo.core.repository.RepositoryType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class RawUploadHandlerTest {

    private static final RepositoryConfig REPO = new RepositoryConfig(
            UUID.randomUUID(), "raw-hosted", "raw", RepositoryType.HOSTED, true, "default", Map.of());

    @Mock
    private RawRequestHandler requestHandler;

    private RawUploadHandler handler;

    @BeforeEach
    void setUp() {
        handler = new RawUploadHandler(requestHandler);
        lenient()
                .when(requestHandler.putContent(
                        eq(REPO), anyString(), any(InputStream.class), anyLong(), anyString(), any(), any()))
                .thenAnswer(invocation -> new CreatedResponse(invocation.getArgument(1), Map.of()));
    }

    private static UploadFile file(String filename) {
        return new UploadFile(
                "file", filename, "application/zip", () -> new ByteArrayInputStream(new byte[] {1}), 1);
    }

    @Test
    void upload_withDirectory_targetsDirectoryPlusFilename() {
        var result = handler.handleUpload(
                REPO, new ComponentUpload(Map.of("directory", "releases/v1"), List.of(file("app.zip")), "admin", "ip"));

        assertInstanceOf(CreatedResponse.class, result);
        verify(requestHandler)
                .putContent(eq(REPO), eq("releases/v1/app.zip"), any(InputStream.class), anyLong(), anyString(), any(), any());
    }

    @Test
    void upload_withExplicitPath_usesPathDirectly() {
        var result = handler.handleUpload(
                REPO, new ComponentUpload(Map.of("path", "/some/dir/renamed.zip"), List.of(file("app.zip")), "admin", "ip"));

        assertInstanceOf(CreatedResponse.class, result);
        verify(requestHandler)
                .putContent(eq(REPO), eq("some/dir/renamed.zip"), any(InputStream.class), anyLong(), anyString(), any(), any());
    }

    @Test
    void upload_multipleFilesWithExplicitPath_returnsError() {
        var result = handler.handleUpload(
                REPO,
                new ComponentUpload(Map.of("path", "x.zip"), List.of(file("a.zip"), file("b.zip")), "admin", "ip"));

        assertInstanceOf(ErrorResponse.class, result);
        verifyNoInteractions(requestHandler);
    }

    @Test
    void upload_withoutDirectory_usesFilenameAsPath() {
        var result = handler.handleUpload(
                REPO, new ComponentUpload(Map.of(), List.of(file("standalone.bin")), "admin", "ip"));

        assertInstanceOf(CreatedResponse.class, result);
        verify(requestHandler)
                .putContent(eq(REPO), eq("standalone.bin"), any(InputStream.class), anyLong(), anyString(), any(), any());
    }
}
