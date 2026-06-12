package de.bsnsoft.megarepo.format.raw;

import de.bsnsoft.megarepo.core.format.ComponentUpload;
import de.bsnsoft.megarepo.core.format.ComponentUpload.UploadFile;
import de.bsnsoft.megarepo.core.format.ComponentUploadHandler;
import de.bsnsoft.megarepo.core.format.FormatResponse;
import de.bsnsoft.megarepo.core.format.FormatResponse.ErrorResponse;
import de.bsnsoft.megarepo.core.repository.RepositoryConfig;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;

/**
 * Manual upload (Web-UI / REST) for raw hosted repositories — equivalent to a
 * direct {@code PUT /repository/<repo>/<path>}.
 *
 * <p>Fields: optional {@code directory} (prefix for all files), optional
 * {@code path} (full target path, single file only — overrides directory).
 */
@Component
public class RawUploadHandler implements ComponentUploadHandler {

    private final RawRequestHandler requestHandler;

    public RawUploadHandler(RawRequestHandler requestHandler) {
        this.requestHandler = requestHandler;
    }

    @Override
    public FormatResponse handleUpload(RepositoryConfig repo, ComponentUpload upload) {
        if (upload.files().isEmpty()) {
            return new ErrorResponse(400, "No file uploaded");
        }

        String explicitPath = upload.field("path");
        if (explicitPath != null && upload.files().size() > 1) {
            return new ErrorResponse(400, "'path' can only be used with a single file — use 'directory' instead");
        }

        String directory = normalizeDirectory(upload.field("directory"));

        FormatResponse last = null;
        for (UploadFile file : upload.files()) {
            String target = explicitPath != null ? stripLeadingSlashes(explicitPath) : directory + file.filename();
            if (target.isBlank()) {
                return new ErrorResponse(400, "Cannot determine target path for upload");
            }

            try (InputStream in = file.content().open()) {
                last = requestHandler.putContent(
                        repo,
                        target,
                        in,
                        file.size(),
                        file.contentType() != null ? file.contentType() : "application/octet-stream",
                        upload.username(),
                        upload.clientIp());
            } catch (IOException e) {
                return new ErrorResponse(400, "Failed to read uploaded file '" + file.filename() + "': " + e.getMessage());
            }
            if (last instanceof ErrorResponse) {
                return last;
            }
        }
        return last;
    }

    private static String normalizeDirectory(String directory) {
        if (directory == null) {
            return "";
        }
        String normalized = stripLeadingSlashes(directory.trim());
        if (!normalized.isEmpty() && !normalized.endsWith("/")) {
            normalized += "/";
        }
        return normalized;
    }

    private static String stripLeadingSlashes(String value) {
        int i = 0;
        while (i < value.length() && value.charAt(i) == '/') {
            i++;
        }
        return value.substring(i);
    }
}
