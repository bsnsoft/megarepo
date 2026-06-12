package de.bsnsoft.megarepo.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.bsnsoft.megarepo.database.entity.BlobStoreEntity;
import de.bsnsoft.megarepo.database.entity.RepositoryEntity;
import de.bsnsoft.megarepo.database.repository.BlobStoreJpaRepository;
import de.bsnsoft.megarepo.database.repository.RepositoryJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end smoke for the NuGet V3 format:
 * service index → push (multipart PUT, lowercase flat-container layout) →
 * version list → byte-identical download → registrations → 409 on re-push.
 */
class NugetRepositoryIntegrationTest extends BaseIntegrationTest {

    private static final String REPO_NAME = "nuget-integration-test";
    private static final String BLOB_STORE_NAME = "default";

    @Autowired
    private RepositoryJpaRepository repositoryJpaRepository;

    @Autowired
    private BlobStoreJpaRepository blobStoreJpaRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        if (blobStoreJpaRepository.findById(BLOB_STORE_NAME).isEmpty()) {
            var blobStore = new BlobStoreEntity();
            blobStore.setName(BLOB_STORE_NAME);
            blobStore.setType("file");
            blobStore.setConfig(Map.of("path", "data/blobs/default"));
            blobStore.setCreatedAt(Instant.now());
            blobStore.setUpdatedAt(Instant.now());
            blobStoreJpaRepository.save(blobStore);
        }

        if (repositoryJpaRepository.findByName(REPO_NAME).isEmpty()) {
            var repo = new RepositoryEntity();
            repo.setName(REPO_NAME);
            repo.setFormat("nuget");
            repo.setType("HOSTED");
            repo.setOnline(true);
            repo.setBlobStoreName(BLOB_STORE_NAME);
            repo.setAttributes(Map.of());
            repo.setCreatedAt(Instant.now());
            repo.setUpdatedAt(Instant.now());
            repositoryJpaRepository.save(repo);
        }
    }

    @Test
    void serviceIndexIsServed() throws IOException {
        ResponseEntity<String> response =
                restTemplate.getForEntity(repositoryUrl(REPO_NAME, "index.json"), String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        JsonNode root = objectMapper.readTree(response.getBody());
        assertEquals("3.0.0", root.path("version").asText());

        List<String> types = new ArrayList<>();
        root.path("resources").forEach(r -> types.add(r.path("@type").asText()));
        assertTrue(types.contains("PackageBaseAddress/3.0.0"), "service index must offer the flat container");
        assertTrue(types.contains("PackagePublish/2.0.0"), "service index must offer the push endpoint");
        assertTrue(types.contains("SearchQueryService"), "service index must offer search");

        for (JsonNode resource : root.path("resources")) {
            assertTrue(resource.path("@id").asText().contains("/repository/" + REPO_NAME + "/"),
                    "all resources must point at the repository");
        }
    }

    @Test
    void pushDownloadRoundtripWithLowercasePaths() throws IOException {
        byte[] nupkg = buildNupkg("IT.Sample", "1.0.0");

        // Push: multipart PUT to api/v2/package (dotnet nuget push shape)
        ResponseEntity<String> pushResponse = push(nupkg);
        assertEquals(HttpStatus.CREATED, pushResponse.getStatusCode(),
                "push should return 201, body: " + pushResponse.getBody());

        // Version list, lowercase id
        ResponseEntity<String> versions = restTemplate.getForEntity(
                repositoryUrl(REPO_NAME, "v3-flatcontainer/it.sample/index.json"), String.class);
        assertEquals(HttpStatus.OK, versions.getStatusCode());
        JsonNode versionsRoot = objectMapper.readTree(versions.getBody());
        assertEquals("1.0.0", versionsRoot.path("versions").get(0).asText());

        // Byte-identical download via lowercase flat-container path
        ResponseEntity<byte[]> download = restTemplate.getForEntity(
                repositoryUrl(REPO_NAME, "v3-flatcontainer/it.sample/1.0.0/it.sample.1.0.0.nupkg"),
                byte[].class);
        assertEquals(HttpStatus.OK, download.getStatusCode());
        assertArrayEquals(nupkg, download.getBody(), "downloaded package must be byte-identical");

        // Manifest is available from the flat container as well
        ResponseEntity<String> nuspec = restTemplate.getForEntity(
                repositoryUrl(REPO_NAME, "v3-flatcontainer/it.sample/1.0.0/it.sample.nuspec"),
                String.class);
        assertEquals(HttpStatus.OK, nuspec.getStatusCode());
        assertNotNull(nuspec.getBody());
        assertTrue(nuspec.getBody().contains("<id>IT.Sample</id>"));

        // Registration index carries the catalog entry
        ResponseEntity<String> registration = restTemplate.getForEntity(
                repositoryUrl(REPO_NAME, "v3/registrations/it.sample/index.json"), String.class);
        assertEquals(HttpStatus.OK, registration.getStatusCode());
        JsonNode regRoot = objectMapper.readTree(registration.getBody());
        JsonNode catalogEntry = regRoot.path("items").get(0).path("items").get(0).path("catalogEntry");
        assertEquals("IT.Sample", catalogEntry.path("id").asText());
        assertEquals("1.0.0", catalogEntry.path("version").asText());

        // Re-push of the same version must be rejected (immutability)
        ResponseEntity<String> rePush = push(nupkg);
        assertEquals(HttpStatus.CONFLICT, rePush.getStatusCode(),
                "re-pushing an existing version must return 409");
    }

    @Test
    void searchFindsPushedPackage() throws IOException {
        push(buildNupkg("Search.Target", "2.5.0"));

        ResponseEntity<String> response = restTemplate.getForEntity(
                repositoryUrl(REPO_NAME, "v3/search") + "?q=search.target", String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        JsonNode root = objectMapper.readTree(response.getBody());
        assertTrue(root.path("totalHits").asInt() >= 1);
        assertEquals("Search.Target", root.path("data").get(0).path("id").asText());
    }

    @Test
    void unknownPackageReturns404() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                repositoryUrl(REPO_NAME, "v3-flatcontainer/does.not.exist/index.json"), String.class);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    private ResponseEntity<String> push(byte[] nupkg) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("package", new ByteArrayResource(nupkg) {
            @Override
            public String getFilename() {
                return "package.nupkg";
            }
        });

        return restTemplate.exchange(
                repositoryUrl(REPO_NAME, "api/v2/package"),
                HttpMethod.PUT,
                new HttpEntity<>(body, headers),
                String.class);
    }

    private static byte[] buildNupkg(String id, String version) {
        String nuspec = """
                <?xml version="1.0" encoding="utf-8"?>
                <package xmlns="http://schemas.microsoft.com/packaging/2013/05/nuspec.xsd">
                  <metadata>
                    <id>%s</id>
                    <version>%s</version>
                    <authors>Integration Test</authors>
                    <description>MegaRepo NuGet integration test package</description>
                  </metadata>
                </package>
                """.formatted(id, version);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
                ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(id + ".nuspec"));
            zip.write(nuspec.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("lib/net8.0/" + id + ".dll"));
            zip.write(new byte[] {0x4d, 0x5a});
            zip.closeEntry();
            zip.finish();
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
