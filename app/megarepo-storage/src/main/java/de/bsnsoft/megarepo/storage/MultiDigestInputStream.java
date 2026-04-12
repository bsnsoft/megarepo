package de.bsnsoft.megarepo.storage;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

public class MultiDigestInputStream extends FilterInputStream {

    private final MessageDigest md5;
    private final MessageDigest sha1;
    private final MessageDigest sha256;
    private final MessageDigest sha512;
    private long bytesRead = 0;

    public MultiDigestInputStream(InputStream in) throws NoSuchAlgorithmException {
        super(in);
        this.md5 = MessageDigest.getInstance("MD5");
        this.sha1 = MessageDigest.getInstance("SHA-1");
        this.sha256 = MessageDigest.getInstance("SHA-256");
        this.sha512 = MessageDigest.getInstance("SHA-512");
    }

    @Override
    public int read() throws IOException {
        int b = super.read();
        if (b != -1) {
            md5.update((byte) b);
            sha1.update((byte) b);
            sha256.update((byte) b);
            sha512.update((byte) b);
            bytesRead++;
        }
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int n = super.read(b, off, len);
        if (n > 0) {
            md5.update(b, off, n);
            sha1.update(b, off, n);
            sha256.update(b, off, n);
            sha512.update(b, off, n);
            bytesRead += n;
        }
        return n;
    }

    public Map<String, String> getChecksums() {
        HexFormat hex = HexFormat.of();
        Map<String, String> checksums = new LinkedHashMap<>();
        checksums.put("md5", hex.formatHex(md5.digest()));
        checksums.put("sha1", hex.formatHex(sha1.digest()));
        checksums.put("sha256", hex.formatHex(sha256.digest()));
        checksums.put("sha512", hex.formatHex(sha512.digest()));
        return checksums;
    }

    public long getBytesRead() {
        return bytesRead;
    }
}
