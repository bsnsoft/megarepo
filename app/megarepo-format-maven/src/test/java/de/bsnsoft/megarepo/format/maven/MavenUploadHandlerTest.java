package de.bsnsoft.megarepo.format.maven;

import de.bsnsoft.megarepo.core.format.ComponentUpload;
import de.bsnsoft.megarepo.core.format.ComponentUpload.UploadFile;
import de.bsnsoft.megarepo.core.format.FormatResponse;
import de.bsnsoft.megarepo.core.format.FormatResponse.CreatedResponse;
import de.bsnsoft.megarepo.core.format.FormatResponse.ErrorResponse;
import de.bsnsoft.megarepo.core.repository.RepositoryConfig;
import de.bsnsoft.megarepo.core.repository.RepositoryType;
import de.bsnsoft.megarepo.format.maven.metadata.MavenMetadataGenerator;
import de.bsnsoft.megarepo.format.maven.pom.PomParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class MavenUploadHandlerTest {

    private static final UUID REPO_ID = UUID.randomUUID();
    private static final RepositoryConfig REPO = new RepositoryConfig(
            REPO_ID, "maven-releases", "maven2", RepositoryType.HOSTED, true, "default", Map.of());

    @Mock
    private MavenRequestHandler requestHandler;

    @Mock
    private MavenMetadataGenerator metadataGenerator;

    private MavenUploadHandler handler;

    @BeforeEach
    void setUp() {
        handler = new MavenUploadHandler(requestHandler, metadataGenerator, new PomParser());
        lenient()
                .when(requestHandler.putContent(
                        eq(REPO), anyString(), any(InputStream.class), anyLong(), anyString(), any(), any()))
                .thenAnswer(invocation -> new CreatedResponse(invocation.getArgument(1), Map.of()));
    }

    private static UploadFile file(String fieldName, String filename, byte[] content) {
        return new UploadFile(
                fieldName,
                filename,
                "application/octet-stream",
                () -> new ByteArrayInputStream(content),
                content.length);
    }

    private static ComponentUpload upload(Map<String, String> fields, UploadFile... files) {
        return new ComponentUpload(fields, List.of(files), "admin", "127.0.0.1");
    }

    @Test
    void upload_jarWithCoordinates_storesAtMavenPathAndRegeneratesMetadata() {
        var result = handler.handleUpload(
                REPO,
                upload(
                        Map.of("groupId", "com.example", "artifactId", "my-lib", "version", "1.0.0"),
                        file("asset0", "my-lib-1.0.0.jar", new byte[] {1, 2, 3})));

        assertInstanceOf(CreatedResponse.class, result);
        assertEquals("com/example/my-lib/1.0.0/my-lib-1.0.0.jar", ((CreatedResponse) result).path());

        verify(requestHandler)
                .putContent(
                        eq(REPO),
                        eq("com/example/my-lib/1.0.0/my-lib-1.0.0.jar"),
                        any(InputStream.class),
                        eq(3L),
                        anyString(),
                        eq("admin"),
                        eq("127.0.0.1"));
        // metadata is regenerated server-side — a maven client would have uploaded it itself
        verify(metadataGenerator).generateMetadata(REPO_ID, "default", "com.example", "my-lib");
        verify(metadataGenerator, never())
                .generateSnapshotMetadata(any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void upload_withClassifierAndExplicitExtension_buildsClassifierPath() {
        var result = handler.handleUpload(
                REPO,
                upload(
                        Map.of(
                                "groupId", "com.example",
                                "artifactId", "my-lib",
                                "version", "1.0.0",
                                "asset0.classifier", "sources",
                                "asset0.extension", "jar"),
                        file("asset0", "whatever.bin", new byte[] {1})));

        assertInstanceOf(CreatedResponse.class, result);
        verify(requestHandler)
                .putContent(
                        eq(REPO),
                        eq("com/example/my-lib/1.0.0/my-lib-1.0.0-sources.jar"),
                        any(InputStream.class),
                        anyLong(),
                        anyString(),
                        any(),
                        any());
    }

    @Test
    void upload_pomOnly_derivesCoordinatesFromPom() {
        byte[] pom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>org.acme</groupId>
                  <artifactId>widget</artifactId>
                  <version>2.5</version>
                </project>
                """.getBytes(StandardCharsets.UTF_8);

        var result = handler.handleUpload(REPO, upload(Map.of(), file("asset0", "widget-2.5.pom", pom)));

        assertInstanceOf(CreatedResponse.class, result);
        verify(requestHandler)
                .putContent(
                        eq(REPO),
                        eq("org/acme/widget/2.5/widget-2.5.pom"),
                        any(InputStream.class),
                        anyLong(),
                        anyString(),
                        any(),
                        any());
        verify(metadataGenerator).generateMetadata(REPO_ID, "default", "org.acme", "widget");
    }

    @Test
    void upload_generatePom_storesGeneratedPomAlongsideJar() {
        var result = handler.handleUpload(
                REPO,
                upload(
                        Map.of(
                                "groupId", "com.example",
                                "artifactId", "my-lib",
                                "version", "1.0.0",
                                "generatePom", "true"),
                        file("asset0", "my-lib-1.0.0.jar", new byte[] {1})));

        assertInstanceOf(CreatedResponse.class, result);

        ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
        verify(requestHandler, org.mockito.Mockito.times(2))
                .putContent(eq(REPO), pathCaptor.capture(), any(InputStream.class), anyLong(), anyString(), any(), any());
        assertTrue(pathCaptor.getAllValues().contains("com/example/my-lib/1.0.0/my-lib-1.0.0.pom"));
    }

    @Test
    void upload_snapshotVersion_regeneratesSnapshotMetadata() {
        var result = handler.handleUpload(
                REPO,
                upload(
                        Map.of("groupId", "com.example", "artifactId", "my-lib", "version", "1.1-SNAPSHOT"),
                        file("asset0", "my-lib-1.1-SNAPSHOT.jar", new byte[] {1})));

        assertInstanceOf(CreatedResponse.class, result);
        verify(metadataGenerator).generateMetadata(REPO_ID, "default", "com.example", "my-lib");
        verify(metadataGenerator)
                .generateSnapshotMetadata(REPO_ID, "default", "com.example", "my-lib", "1.1-SNAPSHOT");
    }

    @Test
    void upload_missingCoordinatesWithoutPom_returnsError() {
        var result = handler.handleUpload(REPO, upload(Map.of(), file("asset0", "my-lib-1.0.0.jar", new byte[] {1})));

        assertInstanceOf(ErrorResponse.class, result);
        assertEquals(400, ((ErrorResponse) result).statusCode());
        verifyNoInteractions(metadataGenerator);
    }

    @Test
    void upload_noFiles_returnsError() {
        var result = handler.handleUpload(REPO, upload(Map.of("groupId", "g", "artifactId", "a", "version", "1")));

        assertInstanceOf(ErrorResponse.class, result);
    }

    @Test
    void upload_invalidCoordinates_returnsError() {
        var result = handler.handleUpload(
                REPO,
                upload(
                        Map.of("groupId", "com/../evil", "artifactId", "my-lib", "version", "1.0.0"),
                        file("asset0", "my-lib-1.0.0.jar", new byte[] {1})));

        assertInstanceOf(ErrorResponse.class, result);
        assertEquals(400, ((ErrorResponse) result).statusCode());
        verifyNoInteractions(metadataGenerator);
    }

    @Test
    void upload_storeFails_propagatesErrorAndSkipsMetadata() {
        org.mockito.Mockito.when(requestHandler.putContent(
                        eq(REPO), anyString(), any(InputStream.class), anyLong(), anyString(), any(), any()))
                .thenReturn(new ErrorResponse(400, "version policy violation"));

        FormatResponse result = handler.handleUpload(
                REPO,
                upload(
                        Map.of("groupId", "com.example", "artifactId", "my-lib", "version", "1.0.0"),
                        file("asset0", "my-lib-1.0.0.jar", new byte[] {1})));

        assertInstanceOf(ErrorResponse.class, result);
        verifyNoInteractions(metadataGenerator);
    }
}
