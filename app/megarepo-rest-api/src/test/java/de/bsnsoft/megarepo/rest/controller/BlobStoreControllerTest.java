package de.bsnsoft.megarepo.rest.controller;

import de.bsnsoft.megarepo.database.entity.BlobStoreEntity;
import de.bsnsoft.megarepo.database.repository.BlobStoreJpaRepository;
import de.bsnsoft.megarepo.rest.advice.GlobalExceptionHandler;
import de.bsnsoft.megarepo.storage.BlobStoreManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class BlobStoreControllerTest {

    private MockMvc mockMvc;

    @Mock
    private BlobStoreJpaRepository blobStoreJpaRepository;

    @Mock
    private de.bsnsoft.megarepo.database.repository.AssetJpaRepository assetJpaRepository;

    @Mock
    private BlobStoreManager blobStoreManager;

    @BeforeEach
    void setUp() {
        var controller = new BlobStoreController(blobStoreJpaRepository, assetJpaRepository, blobStoreManager);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void listBlobStores() throws Exception {
        var store1 = createBlobStoreEntity("default", "File");
        var store2 = createBlobStoreEntity("s3-store", "S3");

        when(blobStoreJpaRepository.findAll()).thenReturn(List.of(store1, store2));

        mockMvc.perform(get("/api/v1/blobstores"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].name").value("default"))
                .andExpect(jsonPath("$[0].type").value("File"))
                .andExpect(jsonPath("$[1].name").value("s3-store"))
                .andExpect(jsonPath("$[1].type").value("S3"));
    }

    @Test
    void listBlobStores_empty() throws Exception {
        when(blobStoreJpaRepository.findAll()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/blobstores"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void deleteBlobStore() throws Exception {
        when(blobStoreJpaRepository.existsById("default")).thenReturn(true);

        mockMvc.perform(delete("/api/v1/blobstores/default"))
                .andExpect(status().isNoContent());

        verify(blobStoreJpaRepository).deleteById("default");
    }

    @Test
    void deleteBlobStore_notFound_returns404() throws Exception {
        when(blobStoreJpaRepository.existsById("nonexistent")).thenReturn(false);

        mockMvc.perform(delete("/api/v1/blobstores/nonexistent"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").exists());
    }

    private static BlobStoreEntity createBlobStoreEntity(String name, String type) {
        var entity = new BlobStoreEntity();
        entity.setName(name);
        entity.setType(type);
        entity.setState("STARTED");
        entity.setConfig(Map.of("path", "/data/" + name));
        entity.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        entity.setUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        return entity;
    }
}
