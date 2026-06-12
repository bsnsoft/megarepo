package de.bsnsoft.megarepo.format.nuget.push;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultipartNupkgExtractorTest {

    private final MultipartNupkgExtractor extractor = new MultipartNupkgExtractor();

    @Test
    void extract_singleFilePart_returnsExactBytes() throws IOException {
        byte[] payload = binaryPayload();
        byte[] body = multipartBody("ABC123boundary", payload);

        Optional<byte[]> result = extractor.extract(
                "multipart/form-data; boundary=ABC123boundary", body);

        assertTrue(result.isPresent());
        assertArrayEquals(payload, result.get());
    }

    @Test
    void extract_quotedBoundary_works() throws IOException {
        byte[] payload = binaryPayload();
        byte[] body = multipartBody("xYz", payload);

        Optional<byte[]> result = extractor.extract(
                "multipart/form-data; boundary=\"xYz\"", body);

        assertTrue(result.isPresent());
        assertArrayEquals(payload, result.get());
    }

    @Test
    void extract_nonMultipart_passesBodyThrough() {
        byte[] body = binaryPayload();
        Optional<byte[]> result = extractor.extract("application/octet-stream", body);
        assertTrue(result.isPresent());
        assertArrayEquals(body, result.get());
    }

    @Test
    void extract_missingContentType_passesBodyThrough() {
        byte[] body = binaryPayload();
        Optional<byte[]> result = extractor.extract(null, body);
        assertTrue(result.isPresent());
        assertArrayEquals(body, result.get());
    }

    @Test
    void extract_emptyBody_returnsEmpty() {
        assertTrue(extractor.extract("multipart/form-data; boundary=x", new byte[0]).isEmpty());
    }

    @Test
    void extract_malformedMultipart_returnsEmpty() {
        byte[] body = "--x\r\nno terminating boundary".getBytes(StandardCharsets.ISO_8859_1);
        assertTrue(extractor.extract("multipart/form-data; boundary=x", body).isEmpty());
    }

    /** Binary content with CR/LF and boundary-ish bytes — must survive byte-exact. */
    private static byte[] binaryPayload() {
        byte[] payload = new byte[512];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i % 251);
        }
        return payload;
    }

    private static byte[] multipartBody(String boundary, byte[] payload) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"package\"; filename=\"package.nupkg\"\r\n"
                + "Content-Type: application/octet-stream\r\n"
                + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
        out.write(payload);
        out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.ISO_8859_1));
        return out.toByteArray();
    }
}
