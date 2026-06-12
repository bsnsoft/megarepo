package de.bsnsoft.megarepo.format.maven;

import de.bsnsoft.megarepo.core.format.ComponentUpload;
import de.bsnsoft.megarepo.core.format.ComponentUpload.UploadFile;
import de.bsnsoft.megarepo.core.format.ComponentUploadHandler;
import de.bsnsoft.megarepo.core.format.FormatResponse;
import de.bsnsoft.megarepo.core.format.FormatResponse.CreatedResponse;
import de.bsnsoft.megarepo.core.format.FormatResponse.ErrorResponse;
import de.bsnsoft.megarepo.core.repository.RepositoryConfig;
import de.bsnsoft.megarepo.format.maven.metadata.MavenMetadataGenerator;
import de.bsnsoft.megarepo.format.maven.pom.PomParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Manual upload (Web-UI / REST) for Maven hosted repositories.
 *
 * <p>Accepts one or more asset files plus GAV coordinates. Coordinates can
 * alternatively be derived from an uploaded POM ({@code .pom} asset). After
 * storing the assets through the same validation/storage pipeline as
 * {@code mvn deploy} ({@link MavenRequestHandler#putContent}), the
 * {@code maven-metadata.xml} for the artifact is regenerated — something a
 * Maven client normally does itself during deploy.
 *
 * <p>Supported fields:
 * <ul>
 *   <li>{@code groupId}, {@code artifactId}, {@code version} — required unless a POM asset is uploaded</li>
 *   <li>{@code generatePom} — {@code true} to generate a minimal POM if none is uploaded</li>
 *   <li>per file: {@code <field>.extension} (default: from filename), {@code <field>.classifier}</li>
 * </ul>
 */
@Component
public class MavenUploadHandler implements ComponentUploadHandler {

    private static final Logger log = LoggerFactory.getLogger(MavenUploadHandler.class);
    private static final Pattern COORDINATE_PATTERN = Pattern.compile("[a-zA-Z0-9._-]+");

    private final MavenRequestHandler requestHandler;
    private final MavenMetadataGenerator metadataGenerator;
    private final PomParser pomParser;

    public MavenUploadHandler(
            MavenRequestHandler requestHandler,
            MavenMetadataGenerator metadataGenerator,
            PomParser pomParser) {
        this.requestHandler = requestHandler;
        this.metadataGenerator = metadataGenerator;
        this.pomParser = pomParser;
    }

    @Override
    public FormatResponse handleUpload(RepositoryConfig repo, ComponentUpload upload) {
        if (upload.files().isEmpty()) {
            return new ErrorResponse(400, "No file uploaded");
        }

        String groupId = upload.field("groupId");
        String artifactId = upload.field("artifactId");
        String version = upload.field("version");

        // Derive missing coordinates from an uploaded POM, if present
        UploadFile pomFile = findPomFile(upload);
        if ((groupId == null || artifactId == null || version == null) && pomFile != null) {
            try (InputStream in = pomFile.content().open()) {
                Optional<PomParser.PomInfo> pomInfo = pomParser.parsePom(in);
                if (pomInfo.isPresent()) {
                    if (groupId == null) groupId = pomInfo.get().groupId();
                    if (artifactId == null) artifactId = pomInfo.get().artifactId();
                    if (version == null) version = pomInfo.get().version();
                }
            } catch (IOException e) {
                return new ErrorResponse(400, "Failed to read uploaded POM: " + e.getMessage());
            }
        }

        if (groupId == null || artifactId == null || version == null) {
            return new ErrorResponse(
                    400, "Missing coordinates: provide groupId, artifactId and version (or upload a POM file)");
        }
        FormatResponse coordinateError = validateCoordinates(groupId, artifactId, version);
        if (coordinateError != null) {
            return coordinateError;
        }

        String basePath = groupId.replace('.', '/') + "/" + artifactId + "/" + version;
        String mainPath = null;

        // Store all uploaded assets through the regular deploy pipeline
        for (UploadFile file : upload.files()) {
            String extension = resolveExtension(upload, file);
            if (extension == null) {
                return new ErrorResponse(
                        400, "Cannot determine extension for '" + file.filename() + "' — specify it explicitly");
            }
            String classifier = upload.fileField(file, "classifier");
            String path = assetPath(basePath, artifactId, version, classifier, extension);

            FormatResponse stored = storeFile(repo, path, file, upload);
            if (stored instanceof ErrorResponse) {
                return stored;
            }
            // Prefer a non-POM asset as the "main" created path
            if (mainPath == null || (!"pom".equals(extension) && mainPath.endsWith(".pom"))) {
                mainPath = path;
            }
        }

        // Generate a minimal POM if requested and none was uploaded
        if (pomFile == null && Boolean.parseBoolean(upload.field("generatePom"))) {
            String pomPath = assetPath(basePath, artifactId, version, null, "pom");
            byte[] pom = generateMinimalPom(groupId, artifactId, version);
            FormatResponse stored = requestHandler.putContent(
                    repo,
                    pomPath,
                    new ByteArrayInputStream(pom),
                    pom.length,
                    "application/xml",
                    upload.username(),
                    upload.clientIp());
            if (stored instanceof ErrorResponse) {
                return stored;
            }
        }

        // Regenerate maven-metadata.xml — a Maven client uploads this itself
        // during `mvn deploy`, manual uploads must trigger it server-side.
        metadataGenerator.generateMetadata(repo.id(), repo.blobStoreName(), groupId, artifactId);
        if (version.endsWith("-SNAPSHOT")) {
            metadataGenerator.generateSnapshotMetadata(
                    repo.id(), repo.blobStoreName(), groupId, artifactId, version);
        }

        log.info(
                "Manual upload of {}:{}:{} ({} file(s)) to repository {}",
                groupId, artifactId, version, upload.files().size(), repo.name());
        return new CreatedResponse(mainPath != null ? mainPath : basePath, Map.of());
    }

    private FormatResponse storeFile(
            RepositoryConfig repo, String path, UploadFile file, ComponentUpload upload) {
        try (InputStream in = file.content().open()) {
            return requestHandler.putContent(
                    repo,
                    path,
                    in,
                    file.size(),
                    file.contentType() != null ? file.contentType() : "application/octet-stream",
                    upload.username(),
                    upload.clientIp());
        } catch (IOException e) {
            return new ErrorResponse(400, "Failed to read uploaded file '" + file.filename() + "': " + e.getMessage());
        }
    }

    private UploadFile findPomFile(ComponentUpload upload) {
        for (UploadFile file : upload.files()) {
            if ("pom".equals(resolveExtension(upload, file))) {
                return file;
            }
        }
        return null;
    }

    private String resolveExtension(ComponentUpload upload, UploadFile file) {
        String explicit = upload.fileField(file, "extension");
        if (explicit != null) {
            return explicit.startsWith(".") ? explicit.substring(1) : explicit;
        }
        return extensionOf(file.filename());
    }

    private static String extensionOf(String filename) {
        if (filename == null) {
            return null;
        }
        // Handle common multi-part extensions (e.g. tar.gz)
        if (filename.endsWith(".tar.gz")) {
            return "tar.gz";
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return null;
        }
        return filename.substring(dot + 1);
    }

    private static String assetPath(
            String basePath, String artifactId, String version, String classifier, String extension) {
        StringBuilder sb = new StringBuilder(basePath)
                .append('/')
                .append(artifactId)
                .append('-')
                .append(version);
        if (classifier != null && !classifier.isBlank()) {
            sb.append('-').append(classifier.trim());
        }
        return sb.append('.').append(extension).toString();
    }

    private static FormatResponse validateCoordinates(String groupId, String artifactId, String version) {
        if (!COORDINATE_PATTERN.matcher(groupId).matches()) {
            return new ErrorResponse(400, "Invalid groupId: " + groupId);
        }
        if (!COORDINATE_PATTERN.matcher(artifactId).matches()) {
            return new ErrorResponse(400, "Invalid artifactId: " + artifactId);
        }
        if (!COORDINATE_PATTERN.matcher(version).matches()) {
            return new ErrorResponse(400, "Invalid version: " + version);
        }
        return null;
    }

    private static byte[] generateMinimalPom(String groupId, String artifactId, String version) {
        String pom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                </project>
                """.formatted(escapeXml(groupId), escapeXml(artifactId), escapeXml(version));
        return pom.getBytes(StandardCharsets.UTF_8);
    }

    private static String escapeXml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
