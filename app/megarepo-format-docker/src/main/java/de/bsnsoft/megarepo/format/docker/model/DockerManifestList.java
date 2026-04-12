package de.bsnsoft.megarepo.format.docker.model;

import java.util.List;
import java.util.Map;

/**
 * Represents a Docker manifest list (multi-arch / "fat" manifest) or OCI image index.
 *
 * <p>A manifest list contains references to platform-specific manifests. Docker clients
 * pull the manifest list first, then select the appropriate child manifest based on
 * their platform (os/architecture).
 *
 * <p>Media types:
 * <ul>
 *   <li>{@code application/vnd.docker.distribution.manifest.list.v2+json} — Docker V2</li>
 *   <li>{@code application/vnd.oci.image.index.v1+json} — OCI</li>
 * </ul>
 */
public record DockerManifestList(
        int schemaVersion,
        String mediaType,
        List<ManifestReference> manifests) {

    /** A reference to a platform-specific manifest within the list. */
    public record ManifestReference(
            String mediaType,
            String digest,
            long size,
            Platform platform,
            Map<String, String> annotations) {
    }

    /** Platform descriptor for a manifest reference. */
    public record Platform(
            String architecture,
            String os,
            String variant,
            String osVersion,
            List<String> osFeatures,
            List<String> features) {
    }

    /** Checks whether a given media type represents a manifest list / OCI index. */
    public static boolean isManifestListMediaType(String mediaType) {
        if (mediaType == null) {
            return false;
        }
        return mediaType.equals(DockerManifest.MEDIA_TYPE_MANIFEST_LIST)
                || mediaType.equals(DockerManifest.MEDIA_TYPE_OCI_INDEX);
    }
}
