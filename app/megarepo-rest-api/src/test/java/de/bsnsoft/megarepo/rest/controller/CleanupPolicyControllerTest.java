package de.bsnsoft.megarepo.rest.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.bsnsoft.megarepo.database.entity.AssetEntity;
import de.bsnsoft.megarepo.database.entity.CleanupPolicyEntity;
import de.bsnsoft.megarepo.database.entity.RepositoryEntity;
import de.bsnsoft.megarepo.database.repository.AssetJpaRepository;
import de.bsnsoft.megarepo.database.repository.CleanupPolicyJpaRepository;
import de.bsnsoft.megarepo.database.repository.ComponentJpaRepository;
import de.bsnsoft.megarepo.database.repository.RepositoryJpaRepository;
import de.bsnsoft.megarepo.rest.advice.GlobalExceptionHandler;
import de.bsnsoft.megarepo.rest.dto.cleanup.CreateCleanupPolicyRequest;
import de.bsnsoft.megarepo.tasks.cleanup.CleanupPolicyEvaluator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
class CleanupPolicyControllerTest {

    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private CleanupPolicyJpaRepository cleanupPolicyRepository;

    @Mock
    private RepositoryJpaRepository repositoryJpaRepository;

    @Mock
    private AssetJpaRepository assetJpaRepository;

    @Mock
    private ComponentJpaRepository componentJpaRepository;

    @Mock
    private CleanupPolicyEvaluator evaluator;

    @BeforeEach
    void setUp() {
        var controller = new CleanupPolicyController(
                cleanupPolicyRepository, repositoryJpaRepository, assetJpaRepository, componentJpaRepository, evaluator);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void listPolicies_returnsAll() throws Exception {
        var policy1 = createPolicyEntity("cleanup-snapshots", "maven2", Map.of("releaseType", "PRERELEASES"));
        var policy2 = createPolicyEntity("cleanup-old", null, Map.of("lastBlobUpdated", 30));

        when(cleanupPolicyRepository.findAll()).thenReturn(List.of(policy1, policy2));

        mockMvc.perform(get("/api/v1/cleanup-policies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].name").value("cleanup-snapshots"))
                .andExpect(jsonPath("$[0].format").value("maven2"))
                .andExpect(jsonPath("$[1].name").value("cleanup-old"));
    }

    @Test
    void getPolicy_found() throws Exception {
        var entity =
                createPolicyEntity("cleanup-snapshots", "maven2", Map.of("releaseType", "PRERELEASES", "regex", ".*"));

        when(cleanupPolicyRepository.findById("cleanup-snapshots")).thenReturn(Optional.of(entity));

        mockMvc.perform(get("/api/v1/cleanup-policies/cleanup-snapshots"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("cleanup-snapshots"))
                .andExpect(jsonPath("$.format").value("maven2"))
                .andExpect(jsonPath("$.criteria.releaseType").value("PRERELEASES"))
                .andExpect(jsonPath("$.criteria.regex").value(".*"));
    }

    @Test
    void getPolicy_notFound_returns404() throws Exception {
        when(cleanupPolicyRepository.findById("nonexistent")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/cleanup-policies/nonexistent"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void createPolicy_returns201() throws Exception {
        var request = new CreateCleanupPolicyRequest(
                "cleanup-old", "maven2", "Remove old artifacts", Map.of("lastBlobUpdated", 30));

        when(cleanupPolicyRepository.existsById("cleanup-old")).thenReturn(false);

        var saved = createPolicyEntity("cleanup-old", "maven2", Map.of("lastBlobUpdated", 30));
        saved.setNotes("Remove old artifacts");
        when(cleanupPolicyRepository.save(any(CleanupPolicyEntity.class))).thenReturn(saved);

        mockMvc.perform(post("/api/v1/cleanup-policies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/cleanup-policies/cleanup-old"))
                .andExpect(jsonPath("$.name").value("cleanup-old"))
                .andExpect(jsonPath("$.format").value("maven2"))
                .andExpect(jsonPath("$.notes").value("Remove old artifacts"));
    }

    @Test
    void createPolicy_duplicate_returns400() throws Exception {
        var request =
                new CreateCleanupPolicyRequest("cleanup-old", "maven2", "notes", Map.of("lastBlobUpdated", 30));

        when(cleanupPolicyRepository.existsById("cleanup-old")).thenReturn(true);

        mockMvc.perform(post("/api/v1/cleanup-policies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updatePolicy_returns200() throws Exception {
        var existing = createPolicyEntity("cleanup-old", "maven2", Map.of("lastBlobUpdated", 30));
        when(cleanupPolicyRepository.findById("cleanup-old")).thenReturn(Optional.of(existing));

        var request = new CreateCleanupPolicyRequest(
                "cleanup-old", "maven2", "Updated notes", Map.of("lastBlobUpdated", 60));

        var updated = createPolicyEntity("cleanup-old", "maven2", Map.of("lastBlobUpdated", 60));
        updated.setNotes("Updated notes");
        when(cleanupPolicyRepository.save(any(CleanupPolicyEntity.class))).thenReturn(updated);

        mockMvc.perform(put("/api/v1/cleanup-policies/cleanup-old")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("cleanup-old"))
                .andExpect(jsonPath("$.notes").value("Updated notes"));
    }

    @Test
    void updatePolicy_notFound_returns404() throws Exception {
        when(cleanupPolicyRepository.findById("nonexistent")).thenReturn(Optional.empty());

        var request = new CreateCleanupPolicyRequest("nonexistent", null, null, Map.of("lastBlobUpdated", 10));

        mockMvc.perform(put("/api/v1/cleanup-policies/nonexistent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletePolicy_returns204() throws Exception {
        when(cleanupPolicyRepository.existsById("cleanup-old")).thenReturn(true);

        mockMvc.perform(delete("/api/v1/cleanup-policies/cleanup-old"))
                .andExpect(status().isNoContent());

        verify(cleanupPolicyRepository).deleteById("cleanup-old");
    }

    @Test
    void deletePolicy_notFound_returns404() throws Exception {
        when(cleanupPolicyRepository.existsById("nonexistent")).thenReturn(false);

        mockMvc.perform(delete("/api/v1/cleanup-policies/nonexistent"))
                .andExpect(status().isNotFound());
    }

    @Test
    void preview_returnsMatchingAssets() throws Exception {
        var policy = createPolicyEntity("cleanup-old", null, Map.of("lastBlobUpdated", 30));
        var repo = createRepositoryEntity("maven-releases");
        var asset = createAssetEntity(repo.getId(), "com/example/lib/1.0/lib-1.0.jar", 1024L);

        when(cleanupPolicyRepository.findById("cleanup-old")).thenReturn(Optional.of(policy));
        when(repositoryJpaRepository.findByName("maven-releases")).thenReturn(Optional.of(repo));

        var assetPage = new PageImpl<>(List.of(asset));
        when(assetJpaRepository.findByRepositoryId(eq(repo.getId()), any(Pageable.class))).thenReturn(assetPage);

        when(evaluator.evaluateForDeletion(eq(policy), any(), eq(repo.getId()), eq(componentJpaRepository)))
                .thenReturn(List.of(asset));

        mockMvc.perform(post("/api/v1/cleanup-policies/cleanup-old/preview")
                        .param("repository", "maven-releases"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.totalSize").value(1024))
                .andExpect(jsonPath("$.assetsToDelete", hasSize(1)))
                .andExpect(jsonPath("$.assetsToDelete[0].path").value("com/example/lib/1.0/lib-1.0.jar"));
    }

    @Test
    void preview_policyNotFound_returns404() throws Exception {
        when(cleanupPolicyRepository.findById("nonexistent")).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/v1/cleanup-policies/nonexistent/preview")
                        .param("repository", "maven-releases"))
                .andExpect(status().isNotFound());
    }

    @Test
    void preview_repositoryNotFound_returns404() throws Exception {
        var policy = createPolicyEntity("cleanup-old", null, Map.of("lastBlobUpdated", 30));
        when(cleanupPolicyRepository.findById("cleanup-old")).thenReturn(Optional.of(policy));
        when(repositoryJpaRepository.findByName("nonexistent")).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/v1/cleanup-policies/cleanup-old/preview")
                        .param("repository", "nonexistent"))
                .andExpect(status().isNotFound());
    }

    private static CleanupPolicyEntity createPolicyEntity(String name, String format, Map<String, Object> criteria) {
        var entity = new CleanupPolicyEntity();
        entity.setName(name);
        entity.setFormat(format);
        entity.setCriteria(criteria);
        entity.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        return entity;
    }

    private static RepositoryEntity createRepositoryEntity(String name) {
        var entity = new RepositoryEntity();
        entity.setId(UUID.randomUUID());
        entity.setName(name);
        entity.setFormat("maven2");
        entity.setType("HOSTED");
        entity.setOnline(true);
        entity.setBlobStoreName("default");
        entity.setAttributes(Map.of());
        entity.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        entity.setUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        return entity;
    }

    private static AssetEntity createAssetEntity(UUID repoId, String path, long size) {
        var entity = new AssetEntity();
        entity.setId(UUID.randomUUID());
        entity.setRepositoryId(repoId);
        entity.setFormat("maven2");
        entity.setPath(path);
        entity.setSize(size);
        entity.setContentType("application/java-archive");
        entity.setLastModified(Instant.parse("2025-12-01T00:00:00Z"));
        entity.setCreatedAt(Instant.parse("2025-12-01T00:00:00Z"));
        entity.setUpdatedAt(Instant.parse("2025-12-01T00:00:00Z"));
        return entity;
    }
}
