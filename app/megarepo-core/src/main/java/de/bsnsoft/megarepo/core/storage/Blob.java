package de.bsnsoft.megarepo.core.storage;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;

public record Blob(BlobRef ref, InputStream inputStream, BlobProperties properties) implements Closeable {

    @Override
    public void close() throws IOException {
        inputStream.close();
    }
}
