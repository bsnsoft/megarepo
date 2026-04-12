package de.bsnsoft.megarepo.format.docker.model;

import java.util.List;
import java.util.Map;

/**
 * Represents a Docker image manifest (OCI / Docker V2 Schema 2).
 * This is a lightweight model for parsing manifests to extract layer and config references.
 */
public record DockerManifest(
        int schemaVersion,
        String mediaType,
        Descriptor config,
        List<Descriptor> layers) {

    /** A content-addressable descriptor referencing a blob. */
    public record Descriptor(
            String mediaType,
            String digest,
            long size,
            Map<String, String> annotations) {
    }

    /** Docker V2 Schema 2 manifest media type. */
    public static final String MEDIA_TYPE_MANIFEST_V2 = "application/vnd.docker.distribution.manifest.v2+json";

    /** OCI image manifest media type. */
    public static final String MEDIA_TYPE_OCI_MANIFEST = "application/vnd.oci.image.manifest.v1+json";

    /** Docker manifest list (multi-arch) media type. */
    public static final String MEDIA_TYPE_MANIFEST_LIST = "application/vnd.docker.distribution.manifest.list.v2+json";

    /** OCI index (multi-arch) media type. */
    public static final String MEDIA_TYPE_OCI_INDEX = "application/vnd.oci.image.index.v1+json";
}
