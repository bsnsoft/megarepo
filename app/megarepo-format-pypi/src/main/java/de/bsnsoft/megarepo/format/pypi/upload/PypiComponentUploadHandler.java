package de.bsnsoft.megarepo.format.pypi.upload;

import de.bsnsoft.megarepo.core.format.ComponentUpload;
import de.bsnsoft.megarepo.core.format.ComponentUpload.UploadFile;
import de.bsnsoft.megarepo.core.format.ComponentUploadHandler;
import de.bsnsoft.megarepo.core.format.FormatResponse;
import de.bsnsoft.megarepo.core.format.FormatResponse.ErrorResponse;
import de.bsnsoft.megarepo.core.repository.RepositoryConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Manual upload (Web-UI / REST) for PyPI hosted repositories.
 *
 * <p>Accepts a distribution file (wheel or sdist) plus optional
 * {@code name}/{@code version} fields. If the fields are omitted, name and
 * version are derived from the standard distribution filename
 * ({@code name-version[-tags].whl} / {@code name-version.tar.gz}). Storage is
 * the same pipeline twine uploads go through
 * ({@link PypiUploadHandler#storeDistribution}); the simple index is
 * generated dynamically, so no metadata regeneration is needed.
 */
@Component
public class PypiComponentUploadHandler implements ComponentUploadHandler {

    private static final Logger log = LoggerFactory.getLogger(PypiComponentUploadHandler.class);

    // name-version(-buildtag)?-pythontag-abitag-platformtag.whl
    private static final Pattern WHEEL_PATTERN =
            Pattern.compile("^(?<name>.+?)-(?<version>[^-]+)(-\\d[^-]*)?-[^-]+-[^-]+-[^-]+\\.whl$");
    // name-version.tar.gz / name-version.zip
    private static final Pattern SDIST_PATTERN =
            Pattern.compile("^(?<name>.+)-(?<version>[^-]+)\\.(tar\\.gz|zip)$");

    private final PypiUploadHandler uploadHandler;

    public PypiComponentUploadHandler(PypiUploadHandler uploadHandler) {
        this.uploadHandler = uploadHandler;
    }

    @Override
    public FormatResponse handleUpload(RepositoryConfig repo, ComponentUpload upload) {
        if (upload.files().size() != 1) {
            return new ErrorResponse(400, "Exactly one distribution file (.whl / .tar.gz) must be uploaded");
        }
        UploadFile file = upload.files().getFirst();
        String filename = file.filename();

        String name = upload.field("name");
        String version = upload.field("version");

        if ((name == null || version == null) && filename != null) {
            Matcher matcher = matchDistributionFilename(filename);
            if (matcher != null) {
                if (name == null) name = matcher.group("name");
                if (version == null) version = matcher.group("version");
            }
        }

        if (name == null || version == null) {
            return new ErrorResponse(
                    400,
                    "Cannot determine package name/version from filename — provide 'name' and 'version' fields");
        }

        String contentType = file.contentType() != null ? file.contentType() : "application/octet-stream";

        try (InputStream in = file.content().open()) {
            FormatResponse result = uploadHandler.storeDistribution(
                    repo, name, version, filename, in, file.size(), contentType,
                    upload.username(), upload.clientIp());
            if (result instanceof FormatResponse.CreatedResponse) {
                log.info("Manual upload of PyPI package {}=={} to repository {}", name, version, repo.name());
            }
            return result;
        } catch (IOException e) {
            return new ErrorResponse(400, "Failed to read uploaded file: " + e.getMessage());
        }
    }

    private static Matcher matchDistributionFilename(String filename) {
        Matcher wheel = WHEEL_PATTERN.matcher(filename);
        if (wheel.matches()) {
            return wheel;
        }
        Matcher sdist = SDIST_PATTERN.matcher(filename);
        if (sdist.matches()) {
            return sdist;
        }
        return null;
    }
}
