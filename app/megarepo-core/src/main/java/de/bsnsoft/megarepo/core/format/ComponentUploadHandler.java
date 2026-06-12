package de.bsnsoft.megarepo.core.format;

import de.bsnsoft.megarepo.core.repository.RepositoryConfig;

/**
 * Format-specific handler for manual uploads to hosted repositories
 * (Web-UI upload page / {@code POST /api/v1/components/upload}).
 *
 * <p>Implementations translate the generic upload (fields + files) into the
 * same storage operations the format performs for a native client publish
 * ({@code mvn deploy}, {@code npm publish}, {@code twine upload}, raw PUT) —
 * including any metadata (re)generation the format requires.
 *
 * <p>Formats whose publish semantics do not map onto a file upload (e.g.
 * Docker manifest/layer pushes) simply do not provide a handler — see
 * {@link FormatPlugin#getComponentUploadHandler()}.
 */
public interface ComponentUploadHandler {

    /**
     * Processes a manual upload into the given <b>hosted</b> repository.
     *
     * @return {@link FormatResponse.CreatedResponse} on success (path = main
     *         created asset path), or {@link FormatResponse.ErrorResponse}
     *         with a client-friendly message on validation failure.
     */
    FormatResponse handleUpload(RepositoryConfig repo, ComponentUpload upload);
}
