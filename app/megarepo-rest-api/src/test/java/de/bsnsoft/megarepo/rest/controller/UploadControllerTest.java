package de.bsnsoft.megarepo.rest.controller;

import de.bsnsoft.megarepo.core.exception.NotFoundException;
import de.bsnsoft.megarepo.core.exception.ValidationException;
import de.bsnsoft.megarepo.core.format.ComponentUpload;
import de.bsnsoft.megarepo.core.format.ComponentUploadHandler;
import de.bsnsoft.megarepo.core.format.FormatPlugin;
import de.bsnsoft.megarepo.core.format.FormatRegistry;
import de.bsnsoft.megarepo.core.format.FormatResponse.CreatedResponse;
import de.bsnsoft.megarepo.core.format.FormatResponse.ErrorResponse;
import de.bsnsoft.megarepo.core.repository.RepositoryConfig;
import de.bsnsoft.megarepo.core.repository.RepositoryConfigService;
import de.bsnsoft.megarepo.core.repository.RepositoryType;
import de.bsnsoft.megarepo.repository.ActivityBroadcaster;
import de.bsnsoft.megarepo.repository.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockMultipartHttpServletRequest;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UploadControllerTest {

    private static final UUID REPO_ID = UUID.randomUUID();

    @Mock
    private RepositoryConfigService repositoryConfigService;

    @Mock
    private FormatRegistry formatRegistry;

    @Mock
    private AuditService auditService;

    @Mock
    private ActivityBroadcaster activityBroadcaster;

    @Mock
    private FormatPlugin plugin;

    @Mock
    private ComponentUploadHandler uploadHandler;

    private UploadController controller;

    @BeforeEach
    void setUp() {
        controller = new UploadController(
                repositoryConfigService, formatRegistry, auditService, activityBroadcaster);
    }

    private static RepositoryConfig repo(RepositoryType type, String format) {
        return new RepositoryConfig(REPO_ID, "test-repo", format, type, true, "default", Map.of());
    }

    private static MockMultipartHttpServletRequest multipartRequest() {
        var request = new MockMultipartHttpServletRequest();
        request.addFile(new MockMultipartFile("file", "my-lib-1.0.0.jar", "application/java-archive", new byte[] {1, 2}));
        request.addParameter("groupId", "com.example");
        return request;
    }

    @Test
    void upload_unknownRepository_throwsNotFound() {
        when(repositoryConfigService.getRepository("missing")).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> controller.upload("missing", multipartRequest()));
    }

    @Test
    void upload_proxyRepository_throwsValidation() {
        when(repositoryConfigService.getRepository("test-repo"))
                .thenReturn(Optional.of(repo(RepositoryType.PROXY, "maven2")));

        var ex = assertThrows(
                ValidationException.class, () -> controller.upload("test-repo", multipartRequest()));
        assertEquals(true, ex.getMessage().contains("hosted"));
    }

    @Test
    void upload_formatWithoutUploadHandler_throwsValidation() {
        when(repositoryConfigService.getRepository("test-repo"))
                .thenReturn(Optional.of(repo(RepositoryType.HOSTED, "docker")));
        when(formatRegistry.getPlugin("docker")).thenReturn(plugin);
        when(plugin.getComponentUploadHandler()).thenReturn(Optional.empty());
        when(plugin.getDisplayName()).thenReturn("Docker");

        var ex = assertThrows(
                ValidationException.class, () -> controller.upload("test-repo", multipartRequest()));
        assertEquals(true, ex.getMessage().contains("Docker"));
    }

    @Test
    void upload_success_returns201AndAudits() {
        when(repositoryConfigService.getRepository("test-repo"))
                .thenReturn(Optional.of(repo(RepositoryType.HOSTED, "maven2")));
        when(formatRegistry.getPlugin("maven2")).thenReturn(plugin);
        when(plugin.getComponentUploadHandler()).thenReturn(Optional.of(uploadHandler));
        when(uploadHandler.handleUpload(any(), any()))
                .thenReturn(new CreatedResponse("com/example/my-lib/1.0.0/my-lib-1.0.0.jar", Map.of()));

        var response = controller.upload("test-repo", multipartRequest());

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals("com/example/my-lib/1.0.0/my-lib-1.0.0.jar", response.getBody().path());

        ArgumentCaptor<ComponentUpload> uploadCaptor = ArgumentCaptor.forClass(ComponentUpload.class);
        verify(uploadHandler).handleUpload(any(), uploadCaptor.capture());
        ComponentUpload upload = uploadCaptor.getValue();
        assertEquals("com.example", upload.field("groupId"));
        assertEquals(1, upload.files().size());
        assertEquals("my-lib-1.0.0.jar", upload.files().getFirst().filename());

        verify(auditService)
                .logUpload(anyString(), eq("test-repo"), eq("com/example/my-lib/1.0.0/my-lib-1.0.0.jar"),
                        eq("maven2"), anyLong(), any());
    }

    @Test
    void upload_handlerError_throwsValidation() {
        when(repositoryConfigService.getRepository("test-repo"))
                .thenReturn(Optional.of(repo(RepositoryType.HOSTED, "maven2")));
        when(formatRegistry.getPlugin("maven2")).thenReturn(plugin);
        when(plugin.getComponentUploadHandler()).thenReturn(Optional.of(uploadHandler));
        when(uploadHandler.handleUpload(any(), any())).thenReturn(new ErrorResponse(400, "bad coordinates"));

        var ex = assertThrows(
                ValidationException.class, () -> controller.upload("test-repo", multipartRequest()));
        assertEquals("bad coordinates", ex.getMessage());
    }

    @Test
    void upload_traversalInField_throwsValidation() {
        when(repositoryConfigService.getRepository("test-repo"))
                .thenReturn(Optional.of(repo(RepositoryType.HOSTED, "raw")));
        when(formatRegistry.getPlugin("raw")).thenReturn(plugin);
        when(plugin.getComponentUploadHandler()).thenReturn(Optional.of(uploadHandler));

        var request = new MockMultipartHttpServletRequest();
        request.addFile(new MockMultipartFile("file", "a.zip", "application/zip", new byte[] {1}));
        request.addParameter("directory", "../../etc");

        assertThrows(ValidationException.class, () -> controller.upload("test-repo", request));
    }

    @Test
    void upload_traversalInFilename_isStrippedToBasename() {
        when(repositoryConfigService.getRepository("test-repo"))
                .thenReturn(Optional.of(repo(RepositoryType.HOSTED, "raw")));
        when(formatRegistry.getPlugin("raw")).thenReturn(plugin);
        when(plugin.getComponentUploadHandler()).thenReturn(Optional.of(uploadHandler));
        when(uploadHandler.handleUpload(any(), any())).thenReturn(new CreatedResponse("evil.zip", Map.of()));

        var request = new MockMultipartHttpServletRequest();
        request.addFile(new MockMultipartFile("file", "../../evil.zip", "application/zip", new byte[] {1}));

        controller.upload("test-repo", request);

        ArgumentCaptor<ComponentUpload> uploadCaptor = ArgumentCaptor.forClass(ComponentUpload.class);
        verify(uploadHandler).handleUpload(any(), uploadCaptor.capture());
        assertEquals("evil.zip", uploadCaptor.getValue().files().getFirst().filename());
    }

    @Test
    void upload_noFiles_throwsValidation() {
        when(repositoryConfigService.getRepository("test-repo"))
                .thenReturn(Optional.of(repo(RepositoryType.HOSTED, "maven2")));
        when(formatRegistry.getPlugin("maven2")).thenReturn(plugin);
        when(plugin.getComponentUploadHandler()).thenReturn(Optional.of(uploadHandler));

        var request = new MockMultipartHttpServletRequest();
        request.addParameter("groupId", "com.example");

        assertThrows(ValidationException.class, () -> controller.upload("test-repo", request));
    }
}
