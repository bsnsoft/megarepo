package de.bsnsoft.megarepo.core.format;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * A manual component upload (Web-UI or REST), decoupled from the servlet layer.
 *
 * <p>Text form fields are passed in {@link #fields()}; uploaded files in
 * {@link #files()}. Per-file attributes (e.g. Maven classifier/extension) use
 * the convention {@code <fileFieldName>.<attribute>} in {@link #fields()}.
 */
public record ComponentUpload(
        Map<String, String> fields,
        List<UploadFile> files,
        String username,
        String clientIp) {

    public String field(String name) {
        String value = fields.get(name);
        return value != null && !value.isBlank() ? value.trim() : null;
    }

    public String fileField(UploadFile file, String attribute) {
        String value = fields.get(file.fieldName() + "." + attribute);
        return value != null && !value.isBlank() ? value.trim() : null;
    }

    /**
     * One uploaded file. {@link ContentSupplier#open()} may be called multiple
     * times (e.g. parse a POM first, then store it) — implementations must
     * return a fresh stream on every call.
     */
    public record UploadFile(
            String fieldName,
            String filename,
            String contentType,
            ContentSupplier content,
            long size) {

        @FunctionalInterface
        public interface ContentSupplier {
            InputStream open() throws IOException;
        }
    }
}
