package de.bsnsoft.megarepo.format.nuget.upload;

import de.bsnsoft.megarepo.core.format.ComponentUpload;
import de.bsnsoft.megarepo.core.format.ComponentUpload.UploadFile;
import de.bsnsoft.megarepo.core.format.FormatResponse;
import de.bsnsoft.megarepo.core.format.FormatResponse.CreatedResponse;
import de.bsnsoft.megarepo.core.format.FormatResponse.ErrorResponse;
import de.bsnsoft.megarepo.core.repository.RepositoryConfig;
import de.bsnsoft.megarepo.core.repository.RepositoryType;
import de.bsnsoft.megarepo.format.nuget.TestNupkgs;
import de.bsnsoft.megarepo.format.nuget.push.NugetPushHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NugetUploadHandlerTest {

    private static final RepositoryConfig REPO = new RepositoryConfig(
            UUID.randomUUID(), "nuget-hosted", "nuget", RepositoryType.HOSTED, true, "default", Map.of());

    @Mock
    private NugetPushHandler pushHandler;

    @Test
    void handleUpload_delegatesToPushPipeline() {
        byte[] nupkg = TestNupkgs.nupkg("Web.Upload", "1.0.0", "Uploaded via UI");
        when(pushHandler.storePackage(eq(REPO), any(), eq("admin"), eq("10.0.0.1")))
                .thenReturn(new CreatedResponse("v3-flatcontainer/web.upload/1.0.0/web.upload.1.0.0.nupkg", Map.of()));

        FormatResponse response = new NugetUploadHandler(pushHandler)
                .handleUpload(REPO, upload(nupkg));

        CreatedResponse created = assertInstanceOf(CreatedResponse.class, response);
        assertEquals("v3-flatcontainer/web.upload/1.0.0/web.upload.1.0.0.nupkg", created.path());
        verify(pushHandler).storePackage(eq(REPO), eq(nupkg), eq("admin"), eq("10.0.0.1"));
    }

    @Test
    void handleUpload_requiresExactlyOneFile() {
        FormatResponse response = new NugetUploadHandler(pushHandler)
                .handleUpload(REPO, new ComponentUpload(Map.of(), List.of(), "admin", "10.0.0.1"));

        ErrorResponse error = assertInstanceOf(ErrorResponse.class, response);
        assertEquals(400, error.statusCode());
    }

    private static ComponentUpload upload(byte[] nupkg) {
        return new ComponentUpload(
                Map.of(),
                List.of(new UploadFile(
                        "file", "web.upload.1.0.0.nupkg", "application/octet-stream",
                        () -> new ByteArrayInputStream(nupkg), nupkg.length)),
                "admin",
                "10.0.0.1");
    }
}
