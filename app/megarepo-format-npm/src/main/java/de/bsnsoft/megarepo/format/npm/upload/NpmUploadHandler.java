package de.bsnsoft.megarepo.format.npm.upload;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.bsnsoft.megarepo.core.format.ComponentUpload;
import de.bsnsoft.megarepo.core.format.ComponentUpload.UploadFile;
import de.bsnsoft.megarepo.core.format.ComponentUploadHandler;
import de.bsnsoft.megarepo.core.format.FormatResponse;
import de.bsnsoft.megarepo.core.format.FormatResponse.ErrorResponse;
import de.bsnsoft.megarepo.core.repository.RepositoryConfig;
import de.bsnsoft.megarepo.format.npm.publish.NpmPublishHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

/**
 * Manual upload (Web-UI / REST) for npm hosted repositories.
 *
 * <p>Accepts an npm package tarball ({@code .tgz}, as produced by
 * {@code npm pack}), reads name/version from the embedded
 * {@code package/package.json} and stores it through the same pipeline as
 * {@code npm publish} ({@link NpmPublishHandler#publishTarball}). Registry
 * metadata is generated dynamically on every metadata GET, so no extra
 * regeneration step is needed.
 */
@Component
public class NpmUploadHandler implements ComponentUploadHandler {

    private static final Logger log = LoggerFactory.getLogger(NpmUploadHandler.class);

    private final NpmPublishHandler publishHandler;
    private final NpmTarballReader tarballReader;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public NpmUploadHandler(NpmPublishHandler publishHandler, NpmTarballReader tarballReader) {
        this.publishHandler = publishHandler;
        this.tarballReader = tarballReader;
    }

    @Override
    public FormatResponse handleUpload(RepositoryConfig repo, ComponentUpload upload) {
        if (upload.files().size() != 1) {
            return new ErrorResponse(400, "Exactly one npm package tarball (.tgz) must be uploaded");
        }
        UploadFile file = upload.files().getFirst();

        byte[] tarballData;
        try (InputStream in = file.content().open()) {
            tarballData = in.readAllBytes();
        } catch (IOException e) {
            return new ErrorResponse(400, "Failed to read uploaded file: " + e.getMessage());
        }

        Optional<byte[]> packageJsonBytes;
        try {
            packageJsonBytes = tarballReader.extractPackageJson(tarballData);
        } catch (IOException e) {
            return new ErrorResponse(
                    400, "Not a valid npm package tarball (" + e.getMessage() + ")");
        }
        if (packageJsonBytes.isEmpty()) {
            return new ErrorResponse(
                    400, "Tarball does not contain package/package.json — is this an npm package (npm pack)?");
        }

        JsonNode packageJson;
        try {
            packageJson = objectMapper.readTree(packageJsonBytes.get());
        } catch (IOException e) {
            return new ErrorResponse(400, "Invalid package.json in tarball: " + e.getMessage());
        }

        String name = textField(packageJson, "name");
        String version = textField(packageJson, "version");
        if (name == null || version == null) {
            return new ErrorResponse(400, "package.json must declare both 'name' and 'version'");
        }

        FormatResponse result = publishHandler.publishTarball(
                repo, name, version, packageJson, tarballData, upload.username(), upload.clientIp());
        if (result instanceof FormatResponse.CreatedResponse) {
            log.info("Manual upload of npm package {}@{} to repository {}", name, version, repo.name());
        }
        return result;
    }

    private static String textField(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() && !value.asText().isBlank() ? value.asText().trim() : null;
    }
}
