package de.bsnsoft.megarepo.format.pypi.upload;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PypiComponentUploadHandlerTest {

    private static final RepositoryConfig REPO = new RepositoryConfig(
            UUID.randomUUID(), "pypi-hosted", "pypi", RepositoryType.HOSTED, true, "default", Map.of());

    @Mock
    private PypiUploadHandler uploadHandler;

    private PypiComponentUploadHandler handler;

    @BeforeEach
    void setUp() {
        handler = new PypiComponentUploadHandler(uploadHandler);
    }

    private static ComponentUpload upload(Map<String, String> fields, String filename) {
        UploadFile file = new UploadFile(
                "file", filename, "application/octet-stream", () -> new ByteArrayInputStream(new byte[] {1}), 1);
        return new ComponentUpload(fields, List.of(file), "admin", "127.0.0.1");
    }

    @Test
    void upload_wheel_derivesNameAndVersionFromFilename() {
        when(uploadHandler.storeDistribution(
                        eq(REPO), anyString(), anyString(), anyString(), any(InputStream.class), anyLong(),
                        anyString(), any(), any()))
                .thenReturn(new CreatedResponse("packages/requests-2.31.0-py3-none-any.whl", Map.of()));

        var result = handler.handleUpload(REPO, upload(Map.of(), "requests-2.31.0-py3-none-any.whl"));

        assertInstanceOf(CreatedResponse.class, result);
        verify(uploadHandler)
                .storeDistribution(
                        eq(REPO), eq("requests"), eq("2.31.0"), eq("requests-2.31.0-py3-none-any.whl"),
                        any(InputStream.class), eq(1L), anyString(), eq("admin"), eq("127.0.0.1"));
    }

    @Test
    void upload_sdist_derivesNameAndVersionFromFilename() {
        when(uploadHandler.storeDistribution(
                        eq(REPO), anyString(), anyString(), anyString(), any(InputStream.class), anyLong(),
                        anyString(), any(), any()))
                .thenReturn(new CreatedResponse("packages/my-pkg-1.0.0.tar.gz", Map.of()));

        var result = handler.handleUpload(REPO, upload(Map.of(), "my-pkg-1.0.0.tar.gz"));

        assertInstanceOf(CreatedResponse.class, result);
        verify(uploadHandler)
                .storeDistribution(
                        eq(REPO), eq("my-pkg"), eq("1.0.0"), eq("my-pkg-1.0.0.tar.gz"),
                        any(InputStream.class), anyLong(), anyString(), any(), any());
    }

    @Test
    void upload_explicitFields_overrideFilenameDerivation() {
        when(uploadHandler.storeDistribution(
                        eq(REPO), anyString(), anyString(), anyString(), any(InputStream.class), anyLong(),
                        anyString(), any(), any()))
                .thenReturn(new CreatedResponse("packages/custom.bin", Map.of()));

        var result = handler.handleUpload(
                REPO, upload(Map.of("name", "custom-name", "version", "9.9"), "custom.bin"));

        assertInstanceOf(CreatedResponse.class, result);
        verify(uploadHandler)
                .storeDistribution(
                        eq(REPO), eq("custom-name"), eq("9.9"), eq("custom.bin"),
                        any(InputStream.class), anyLong(), anyString(), any(), any());
    }

    @Test
    void upload_underivableFilenameWithoutFields_returnsError() {
        var result = handler.handleUpload(REPO, upload(Map.of(), "no-version-here.bin"));

        assertInstanceOf(ErrorResponse.class, result);
        assertEquals(400, ((ErrorResponse) result).statusCode());
        verifyNoInteractions(uploadHandler);
    }
}
