package de.bsnsoft.megarepo.format.npm.upload;

import com.fasterxml.jackson.databind.JsonNode;
import de.bsnsoft.megarepo.core.format.ComponentUpload;
import de.bsnsoft.megarepo.core.format.ComponentUpload.UploadFile;
import de.bsnsoft.megarepo.core.format.FormatResponse.CreatedResponse;
import de.bsnsoft.megarepo.core.format.FormatResponse.ErrorResponse;
import de.bsnsoft.megarepo.core.repository.RepositoryConfig;
import de.bsnsoft.megarepo.core.repository.RepositoryType;
import de.bsnsoft.megarepo.format.npm.publish.NpmPublishHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NpmUploadHandlerTest {

    private static final RepositoryConfig REPO = new RepositoryConfig(
            UUID.randomUUID(), "npm-hosted", "npm", RepositoryType.HOSTED, true, "default", Map.of());

    @Mock
    private NpmPublishHandler publishHandler;

    private NpmUploadHandler handler;

    @BeforeEach
    void setUp() {
        handler = new NpmUploadHandler(publishHandler, new NpmTarballReader());
    }

    // --- tar.gz test fixture helpers (ustar format) ---

    static byte[] tarGz(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream tar = new ByteArrayOutputStream();
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            tar.write(tarHeader(entry.getKey(), entry.getValue().length));
            tar.write(entry.getValue());
            int padding = (512 - (entry.getValue().length % 512)) % 512;
            tar.write(new byte[padding]);
        }
        tar.write(new byte[1024]); // two zero blocks = end of archive

        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(gz)) {
            gzip.write(tar.toByteArray());
        }
        return gz.toByteArray();
    }

    private static byte[] tarHeader(String name, int size) {
        byte[] header = new byte[512];
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(nameBytes, 0, header, 0, nameBytes.length);
        writeOctal(header, 100, 8, 0644);       // mode
        writeOctal(header, 108, 8, 0);          // uid
        writeOctal(header, 116, 8, 0);          // gid
        writeOctal(header, 124, 12, size);      // size
        writeOctal(header, 136, 12, 0);         // mtime
        Arrays.fill(header, 148, 156, (byte) ' '); // checksum placeholder
        header[156] = '0';                      // type: regular file
        System.arraycopy("ustar\0".getBytes(StandardCharsets.US_ASCII), 0, header, 257, 6);

        int checksum = 0;
        for (byte b : header) {
            checksum += b & 0xFF;
        }
        byte[] checksumBytes = "%06o\0 ".formatted(checksum).getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(checksumBytes, 0, header, 148, checksumBytes.length);
        return header;
    }

    private static void writeOctal(byte[] header, int offset, int length, long value) {
        byte[] bytes = ("%0" + (length - 1) + "o\0").formatted(value).getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, header, offset, Math.min(bytes.length, length));
    }

    private static ComponentUpload upload(byte[] content, String filename) {
        UploadFile file = new UploadFile(
                "file", filename, "application/gzip", () -> new ByteArrayInputStream(content), content.length);
        return new ComponentUpload(Map.of(), List.of(file), "admin", "127.0.0.1");
    }

    // --- tests ---

    @Test
    void upload_validTarball_publishesWithNameAndVersionFromPackageJson() throws IOException {
        byte[] packageJson = """
                {"name": "my-pkg", "version": "1.2.3", "description": "A test package"}
                """.getBytes(StandardCharsets.UTF_8);
        byte[] tarball = tarGz(Map.of("package/package.json", packageJson));

        when(publishHandler.publishTarball(eq(REPO), eq("my-pkg"), eq("1.2.3"), any(), any(), eq("admin"), eq("127.0.0.1")))
                .thenReturn(new CreatedResponse("-/my-pkg-1.2.3.tgz", Map.of()));

        var result = handler.handleUpload(REPO, upload(tarball, "my-pkg-1.2.3.tgz"));

        assertInstanceOf(CreatedResponse.class, result);

        ArgumentCaptor<JsonNode> metadataCaptor = ArgumentCaptor.forClass(JsonNode.class);
        ArgumentCaptor<byte[]> tarballCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(publishHandler)
                .publishTarball(
                        eq(REPO), eq("my-pkg"), eq("1.2.3"),
                        metadataCaptor.capture(), tarballCaptor.capture(), eq("admin"), eq("127.0.0.1"));
        assertEquals("A test package", metadataCaptor.getValue().get("description").asText());
        assertArrayEquals(tarball, tarballCaptor.getValue());
    }

    @Test
    void upload_scopedPackage_usesFullScopedName() throws IOException {
        byte[] packageJson = """
                {"name": "@acme/tool", "version": "0.1.0"}
                """.getBytes(StandardCharsets.UTF_8);
        byte[] tarball = tarGz(Map.of("package/package.json", packageJson));

        when(publishHandler.publishTarball(eq(REPO), eq("@acme/tool"), eq("0.1.0"), any(), any(), any(), any()))
                .thenReturn(new CreatedResponse("@acme/tool/-/tool-0.1.0.tgz", Map.of()));

        var result = handler.handleUpload(REPO, upload(tarball, "acme-tool-0.1.0.tgz"));

        assertInstanceOf(CreatedResponse.class, result);
    }

    @Test
    void upload_tarballWithoutPackageJson_returnsError() throws IOException {
        byte[] tarball = tarGz(Map.of("package/index.js", "console.log('hi')".getBytes(StandardCharsets.UTF_8)));

        var result = handler.handleUpload(REPO, upload(tarball, "broken.tgz"));

        assertInstanceOf(ErrorResponse.class, result);
        assertEquals(400, ((ErrorResponse) result).statusCode());
        verifyNoInteractions(publishHandler);
    }

    @Test
    void upload_notAGzipFile_returnsError() {
        var result = handler.handleUpload(REPO, upload("definitely not gzip".getBytes(StandardCharsets.UTF_8), "x.tgz"));

        assertInstanceOf(ErrorResponse.class, result);
        assertEquals(400, ((ErrorResponse) result).statusCode());
        verifyNoInteractions(publishHandler);
    }

    @Test
    void upload_packageJsonWithoutVersion_returnsError() throws IOException {
        byte[] packageJson = "{\"name\": \"my-pkg\"}".getBytes(StandardCharsets.UTF_8);
        byte[] tarball = tarGz(Map.of("package/package.json", packageJson));

        var result = handler.handleUpload(REPO, upload(tarball, "my-pkg.tgz"));

        assertInstanceOf(ErrorResponse.class, result);
        verifyNoInteractions(publishHandler);
    }

    @Test
    void upload_multipleFiles_returnsError() {
        UploadFile f1 = new UploadFile("file", "a.tgz", null, () -> new ByteArrayInputStream(new byte[0]), 0);
        UploadFile f2 = new UploadFile("file", "b.tgz", null, () -> new ByteArrayInputStream(new byte[0]), 0);

        var result = handler.handleUpload(REPO, new ComponentUpload(Map.of(), List.of(f1, f2), "admin", "ip"));

        assertInstanceOf(ErrorResponse.class, result);
        verifyNoInteractions(publishHandler);
    }

    @Test
    void tarballReader_skipsOtherEntriesBeforePackageJson() throws IOException {
        byte[] packageJson = "{\"name\":\"p\",\"version\":\"1.0.0\"}".getBytes(StandardCharsets.UTF_8);
        // LinkedHashMap-like ordering via Map.of is unordered — build explicitly ordered archive
        ByteArrayOutputStream tar = new ByteArrayOutputStream();
        byte[] bigFile = new byte[1500];
        tar.write(tarHeader("package/README.md", bigFile.length));
        tar.write(bigFile);
        tar.write(new byte[(512 - (bigFile.length % 512)) % 512]);
        tar.write(tarHeader("package/package.json", packageJson.length));
        tar.write(packageJson);
        tar.write(new byte[(512 - (packageJson.length % 512)) % 512]);
        tar.write(new byte[1024]);

        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(gz)) {
            gzip.write(tar.toByteArray());
        }

        var extracted = new NpmTarballReader().extractPackageJson(gz.toByteArray());
        assertTrue(extracted.isPresent());
        assertArrayEquals(packageJson, extracted.get());
    }
}
