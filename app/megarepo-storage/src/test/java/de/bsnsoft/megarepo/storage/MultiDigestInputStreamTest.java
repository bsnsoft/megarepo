package de.bsnsoft.megarepo.storage;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiDigestInputStreamTest {

    @Test
    void testKnownDataChecksums() throws NoSuchAlgorithmException, IOException {
        byte[] data = "Hello, World!".getBytes(StandardCharsets.UTF_8);
        ByteArrayInputStream bais = new ByteArrayInputStream(data);

        MultiDigestInputStream stream = new MultiDigestInputStream(bais);
        stream.readAllBytes();

        Map<String, String> checksums = stream.getChecksums();

        HexFormat hex = HexFormat.of();
        assertEquals(hex.formatHex(MessageDigest.getInstance("MD5").digest(data)), checksums.get("md5"));
        assertEquals(hex.formatHex(MessageDigest.getInstance("SHA-1").digest(data)), checksums.get("sha1"));
        assertEquals(hex.formatHex(MessageDigest.getInstance("SHA-256").digest(data)), checksums.get("sha256"));
        assertEquals(hex.formatHex(MessageDigest.getInstance("SHA-512").digest(data)), checksums.get("sha512"));
    }

    @Test
    void testEmptyStream() throws NoSuchAlgorithmException, IOException {
        ByteArrayInputStream bais = new ByteArrayInputStream(new byte[0]);

        MultiDigestInputStream stream = new MultiDigestInputStream(bais);
        stream.readAllBytes();

        Map<String, String> checksums = stream.getChecksums();

        HexFormat hex = HexFormat.of();
        assertEquals(hex.formatHex(MessageDigest.getInstance("MD5").digest(new byte[0])), checksums.get("md5"));
        assertEquals(hex.formatHex(MessageDigest.getInstance("SHA-1").digest(new byte[0])), checksums.get("sha1"));
        assertEquals(hex.formatHex(MessageDigest.getInstance("SHA-256").digest(new byte[0])), checksums.get("sha256"));
        assertEquals(hex.formatHex(MessageDigest.getInstance("SHA-512").digest(new byte[0])), checksums.get("sha512"));
        assertEquals(0, stream.getBytesRead());
    }

    @Test
    void testBytesReadCounter() throws NoSuchAlgorithmException, IOException {
        byte[] data = "Hello, World!".getBytes(StandardCharsets.UTF_8);
        ByteArrayInputStream bais = new ByteArrayInputStream(data);

        MultiDigestInputStream stream = new MultiDigestInputStream(bais);
        stream.readAllBytes();

        assertEquals(data.length, stream.getBytesRead());
    }

    @Test
    void testSingleByteRead() throws NoSuchAlgorithmException, IOException {
        byte[] data = "Hi".getBytes(StandardCharsets.UTF_8);
        ByteArrayInputStream bais = new ByteArrayInputStream(data);

        MultiDigestInputStream stream = new MultiDigestInputStream(bais);

        // Read byte by byte
        int b1 = stream.read();
        int b2 = stream.read();
        int eof = stream.read();

        assertEquals('H', b1);
        assertEquals('i', b2);
        assertEquals(-1, eof);
        assertEquals(2, stream.getBytesRead());

        Map<String, String> checksums = stream.getChecksums();
        HexFormat hex = HexFormat.of();
        assertEquals(hex.formatHex(MessageDigest.getInstance("MD5").digest(data)), checksums.get("md5"));
    }
}
