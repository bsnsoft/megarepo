package de.bsnsoft.megarepo.format.docker.firewall;

import de.bsnsoft.megarepo.database.entity.ComponentEntity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DockerPurlMapperTest {

    private final DockerPurlMapper mapper = new DockerPurlMapper();

    @Test
    void declaresDocker() {
        assertEquals("docker", mapper.format());
        assertTrue(mapper.formatAliases().isEmpty());
    }

    @Test
    void aTaggedImageHasNoPurlIdentity() {
        // A tag is mutable: pkg:docker/library/nginx@1.25 would name a moving
        // target. PurlBuilder falls back to the manifest's SHA-256 instead.
        assertTrue(mapper.toPurl(component("library", "nginx", "1.25")).isEmpty());
        assertTrue(mapper.toPurl(component("acme/team", "app", "latest")).isEmpty());
    }

    @Test
    void aDigestPinnedImageAlsoHasNoPurlIdentity() {
        // The digest is a real identity, but it belongs in hash identity — the
        // vulnerabilities live in the layers, and no advisory feed publishes
        // ranges against pkg:docker.
        assertTrue(mapper.toPurl(component("library", "nginx",
                "sha256:0f1c0b0a2e7f6f9a2f0a5f9b6d9d1a0c9e5a2b3c4d5e6f708192a3b4c5d6e7f8")).isEmpty());
    }

    @Test
    void aBareImageNameWithTheEmptyStringNamespaceIsHandled() {
        // DockerCoordinateExtractor writes "" rather than null when the image
        // has no repository prefix.
        assertTrue(mapper.toPurl(component("", "nginx", "1.25")).isEmpty());
    }

    @Test
    void nullComponentYieldsEmpty() {
        assertTrue(mapper.toPurl(null).isEmpty());
    }

    private static ComponentEntity component(String namespace, String name, String version) {
        ComponentEntity component = new ComponentEntity();
        component.setFormat("docker");
        component.setNamespace(namespace);
        component.setName(name);
        component.setVersion(version);
        return component;
    }
}
