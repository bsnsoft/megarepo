package de.bsnsoft.megarepo.format.nuget.upload;

import de.bsnsoft.megarepo.core.format.ComponentUpload;
import de.bsnsoft.megarepo.core.format.ComponentUpload.UploadFile;
import de.bsnsoft.megarepo.core.format.ComponentUploadHandler;
import de.bsnsoft.megarepo.core.format.FormatResponse;
import de.bsnsoft.megarepo.core.format.FormatResponse.ErrorResponse;
import de.bsnsoft.megarepo.core.repository.RepositoryConfig;
import de.bsnsoft.megarepo.format.nuget.push.NugetPushHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;

/**
 * Manual upload (Web-UI / REST) for NuGet hosted repositories.
 *
 * <p>Accepts a single {@code .nupkg}; id/version/metadata are read from the
 * embedded {@code .nuspec} and the package is stored through the same
 * pipeline as {@code dotnet nuget push}
 * ({@link NugetPushHandler#storePackage}).
 */
@Component
public class NugetUploadHandler implements ComponentUploadHandler {

    private static final Logger log = LoggerFactory.getLogger(NugetUploadHandler.class);

    private final NugetPushHandler pushHandler;

    public NugetUploadHandler(NugetPushHandler pushHandler) {
        this.pushHandler = pushHandler;
    }

    @Override
    public FormatResponse handleUpload(RepositoryConfig repo, ComponentUpload upload) {
        if (upload.files().size() != 1) {
            return new ErrorResponse(400, "Exactly one NuGet package (.nupkg) must be uploaded");
        }
        UploadFile file = upload.files().getFirst();

        byte[] nupkgData;
        try (InputStream in = file.content().open()) {
            nupkgData = in.readAllBytes();
        } catch (IOException e) {
            return new ErrorResponse(400, "Failed to read uploaded file: " + e.getMessage());
        }

        FormatResponse result = pushHandler.storePackage(repo, nupkgData, upload.username(), upload.clientIp());
        if (result instanceof FormatResponse.CreatedResponse created) {
            log.info("Manual upload of NuGet package to repository {} ({})", repo.name(), created.path());
        }
        return result;
    }
}
