package de.bsnsoft.megarepo.rest.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.bsnsoft.megarepo.core.format.FormatRegistry;
import de.bsnsoft.megarepo.database.entity.RepositoryEntity;
import de.bsnsoft.megarepo.database.repository.BlobStoreJpaRepository;
import de.bsnsoft.megarepo.database.repository.RepositoryJpaRepository;
import de.bsnsoft.megarepo.rest.advice.GlobalExceptionHandler;
import de.bsnsoft.megarepo.rest.dto.repository.CreateRepositoryRequest;
import de.bsnsoft.megarepo.rest.dto.repository.UpdateRepositoryRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class RepositoryControllerTest {

    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private RepositoryJpaRepository repositoryJpaRepository;

    @Mock
    private BlobStoreJpaRepository blobStoreJpaRepository;

    @Mock
    private de.bsnsoft.megarepo.database.repository.GroupMemberJpaRepository groupMemberJpaRepository;

    @Mock
    private de.bsnsoft.megarepo.database.repository.ComponentJpaRepository componentJpaRepository;

    @Mock
    private de.bsnsoft.megarepo.database.repository.AssetJpaRepository assetJpaRepository;

    @Mock
    private FormatRegistry formatRegistry;

    @BeforeEach
    void setUp() {
        var controller = new RepositoryController(repositoryJpaRepository, blobStoreJpaRepository, groupMemberJpaRepository, componentJpaRepository, assetJpaRepository, formatRegistry);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void listRepositories_returnsAll() throws Exception {
        var repo1 = createRepositoryEntity("maven-releases", "maven2", "HOSTED");
        var repo2 = createRepositoryEntity("npm-hosted", "npm", "HOSTED");

        when(repositoryJpaRepository.findAll()).thenReturn(List.of(repo1, repo2));

        mockMvc.perform(get("/api/v1/repositories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].name").value("maven-releases"))
                .andExpect(jsonPath("$[0].format").value("maven2"))
                .andExpect(jsonPath("$[0].type").value("HOSTED"))
                .andExpect(jsonPath("$[0].url").value("/repository/maven-releases"))
                .andExpect(jsonPath("$[1].name").value("npm-hosted"))
                .andExpect(jsonPath("$[1].format").value("npm"));
    }

    @Test
    void getRepository_found() throws Exception {
        var entity = createRepositoryEntity("maven-releases", "maven2", "HOSTED");
        when(repositoryJpaRepository.findByName("maven-releases")).thenReturn(Optional.of(entity));

        mockMvc.perform(get("/api/v1/repositories/maven-releases"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("maven-releases"))
                .andExpect(jsonPath("$.format").value("maven2"))
                .andExpect(jsonPath("$.type").value("HOSTED"))
                .andExpect(jsonPath("$.online").value(true))
                .andExpect(jsonPath("$.url").value("/repository/maven-releases"));
    }

    @Test
    void getRepository_notFound_returns404() throws Exception {
        when(repositoryJpaRepository.findByName("nonexistent")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/repositories/nonexistent"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void createRepository_returns201() throws Exception {
        var request = new CreateRepositoryRequest(
                "maven-releases", "maven2", "HOSTED", true, "default", Map.of());

        when(repositoryJpaRepository.findByName("maven-releases")).thenReturn(Optional.empty());
        when(formatRegistry.getSupportedFormats()).thenReturn(Set.of("maven2", "npm", "pypi", "raw"));
        when(blobStoreJpaRepository.existsById("default")).thenReturn(true);

        var savedEntity = createRepositoryEntity("maven-releases", "maven2", "HOSTED");
        when(repositoryJpaRepository.save(any(RepositoryEntity.class))).thenReturn(savedEntity);

        mockMvc.perform(post("/api/v1/repositories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/repositories/maven-releases"))
                .andExpect(jsonPath("$.name").value("maven-releases"))
                .andExpect(jsonPath("$.format").value("maven2"))
                .andExpect(jsonPath("$.type").value("HOSTED"));
    }

    @Test
    void updateRepository_returns200() throws Exception {
        var entity = createRepositoryEntity("maven-releases", "maven2", "HOSTED");
        when(repositoryJpaRepository.findByName("maven-releases")).thenReturn(Optional.of(entity));
        when(repositoryJpaRepository.save(any(RepositoryEntity.class))).thenReturn(entity);

        var request = new UpdateRepositoryRequest(
                false, Map.of("maven", Map.of("versionPolicy", "RELEASE")));

        mockMvc.perform(put("/api/v1/repositories/maven-releases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("maven-releases"))
                .andExpect(jsonPath("$.format").value("maven2"));

        verify(repositoryJpaRepository).save(any(RepositoryEntity.class));
    }

    @Test
    void updateRepository_notFound_returns404() throws Exception {
        when(repositoryJpaRepository.findByName("nonexistent")).thenReturn(Optional.empty());

        var request = new UpdateRepositoryRequest(true, Map.of());

        mockMvc.perform(put("/api/v1/repositories/nonexistent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteRepository_returns204() throws Exception {
        var entity = createRepositoryEntity("maven-releases", "maven2", "HOSTED");
        when(repositoryJpaRepository.findByName("maven-releases")).thenReturn(Optional.of(entity));

        mockMvc.perform(delete("/api/v1/repositories/maven-releases"))
                .andExpect(status().isNoContent());

        verify(repositoryJpaRepository).delete(entity);
    }

    private static RepositoryEntity createRepositoryEntity(String name, String format, String type) {
        var entity = new RepositoryEntity();
        entity.setId(UUID.randomUUID());
        entity.setName(name);
        entity.setFormat(format);
        entity.setType(type);
        entity.setOnline(true);
        entity.setBlobStoreName("default");
        entity.setAttributes(Map.of());
        entity.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        entity.setUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        return entity;
    }
}
