package de.bsnsoft.megarepo.format.npm.upload;

import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.zip.GZIPInputStream;

/**
 * Minimal tar.gz reader that extracts {@code package/package.json} from an
 * npm package tarball. npm tarballs (created by {@code npm pack}) always
 * place the package contents under a single {@code package/} root directory;
 * we accept any single top-level directory for robustness.
 *
 * <p>Implements just enough of the POSIX ustar format (512-byte header
 * blocks, name at offset 0, octal size at offset 124) — no external
 * dependency needed.
 */
@Component
public class NpmTarballReader {

    private static final int BLOCK_SIZE = 512;
    private static final int NAME_OFFSET = 0;
    private static final int NAME_LENGTH = 100;
    private static final int SIZE_OFFSET = 124;
    private static final int SIZE_LENGTH = 12;
    private static final int TYPE_OFFSET = 156;
    private static final long MAX_PACKAGE_JSON_SIZE = 5L * 1024 * 1024;

    public Optional<byte[]> extractPackageJson(byte[] tarballGzBytes) throws IOException {
        try (var gzip = new GZIPInputStream(new ByteArrayInputStream(tarballGzBytes));
                var in = new DataInputStream(gzip)) {
            byte[] header = new byte[BLOCK_SIZE];
            while (true) {
                try {
                    in.readFully(header);
                } catch (EOFException e) {
                    return Optional.empty();
                }
                if (isZeroBlock(header)) {
                    return Optional.empty();
                }

                String name = readString(header, NAME_OFFSET, NAME_LENGTH);
                long size = readOctal(header, SIZE_OFFSET, SIZE_LENGTH);
                byte typeFlag = header[TYPE_OFFSET];
                boolean isRegularFile = typeFlag == 0 || typeFlag == '0';

                if (isRegularFile && isPackageJsonEntry(name)) {
                    if (size > MAX_PACKAGE_JSON_SIZE) {
                        throw new IOException("package.json too large: " + size + " bytes");
                    }
                    byte[] content = new byte[(int) size];
                    in.readFully(content);
                    return Optional.of(content);
                }

                skipFully(in, paddedSize(size));
            }
        }
    }

    /** Matches {@code <root>/package.json} for any single top-level directory. */
    private static boolean isPackageJsonEntry(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        String normalized = name.startsWith("./") ? name.substring(2) : name;
        int slash = normalized.indexOf('/');
        return slash > 0 && normalized.substring(slash + 1).equals("package.json");
    }

    private static long paddedSize(long size) {
        long remainder = size % BLOCK_SIZE;
        return remainder == 0 ? size : size + (BLOCK_SIZE - remainder);
    }

    private static void skipFully(InputStream in, long count) throws IOException {
        long remaining = count;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) {
                if (in.read() < 0) {
                    throw new EOFException("Unexpected end of tar archive");
                }
                skipped = 1;
            }
            remaining -= skipped;
        }
    }

    private static boolean isZeroBlock(byte[] block) {
        for (byte b : block) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    private static String readString(byte[] header, int offset, int length) {
        int end = offset;
        while (end < offset + length && header[end] != 0) {
            end++;
        }
        return new String(header, offset, end - offset, StandardCharsets.UTF_8);
    }

    private static long readOctal(byte[] header, int offset, int length) throws IOException {
        String raw = readString(header, offset, length).trim();
        if (raw.isEmpty()) {
            return 0;
        }
        try {
            return Long.parseLong(raw, 8);
        } catch (NumberFormatException e) {
            throw new IOException("Invalid octal field in tar header: '" + raw + "'");
        }
    }
}
