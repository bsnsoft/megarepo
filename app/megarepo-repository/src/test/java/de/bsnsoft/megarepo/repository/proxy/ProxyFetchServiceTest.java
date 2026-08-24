package de.bsnsoft.megarepo.repository.proxy;

import de.bsnsoft.megarepo.core.format.ComponentCoordinateExtractor;
import de.bsnsoft.megarepo.core.format.ComponentCoordinates;
import de.bsnsoft.megarepo.core.format.FormatResponse;
import de.bsnsoft.megarepo.core.format.FormatResponse.ContentResponse;
import de.bsnsoft.megarepo.core.format.FormatResponse.ErrorResponse;
import de.bsnsoft.megarepo.core.repository.RepositoryConfig;
import de.bsnsoft.megarepo.core.repository.RepositoryType;
import de.bsnsoft.megarepo.core.storage.BlobRef;
import de.bsnsoft.megarepo.core.storage.BlobStore;
import de.bsnsoft.megarepo.database.entity.AssetEntity;
import de.bsnsoft.megarepo.database.entity.ComponentEntity;
import de.bsnsoft.megarepo.database.repository.AssetJpaRepository;
import de.bsnsoft.megarepo.database.repository.ComponentJpaRepository;
import de.bsnsoft.megarepo.repository.AuditService;
import de.bsnsoft.megarepo.storage.BlobStoreManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProxyFetchServiceTest {

    @Mock
    private RemoteHttpClient remoteHttpClient;

    @Mock
    private AssetJpaRepository assetRepository;

    @Mock
    private ComponentJpaRepository componentRepository;

    @Mock
    private BlobStoreManager blobStoreManager;

    @Mock
    private NegativeCacheService negativeCacheService;

    @Mock
    private BlacklistService blacklistService;

    @Mock
    private AuditService auditService;

    @Mock
    private BlobStore blobStore;

    @Mock
    private ComponentCoordinateExtractor extractor;

    private ProxyFetchService service;

    private static final UUID REPO_ID = UUID.randomUUID();
    private static final String REMOTE_URL = "https://repo.maven.apache.org/maven2";

    @BeforeEach
    void setUp() {
        service = new ProxyFetchService(
                remoteHttpClient,
                assetRepository,
                componentRepository,
                blobStoreManager,
                negativeCacheService,
                blacklistService,
                auditService);
    }

    @Test
    void successfulFetch_cachesAssetAndReturnsContent() throws IOException {
        RepositoryConfig repo = createProxyRepo();
        String path = "com/example/artifact/1.0/artifact-1.0.jar";
        byte[] content = "jar-content".getBytes();

        when(negativeCacheService.isEnabled(repo)).thenReturn(true);
        when(negativeCacheService.isNegativelyCached(REPO_ID, path)).thenReturn(false);

        var remoteResponse = new RemoteHttpClient.RemoteResponse(
                200, new ByteArrayInputStream(content), content.length, "application/java-archive");
        when(remoteHttpClient.fetch(eq(REMOTE_URL + "/" + path), any())).thenReturn(remoteResponse);

        when(blobStoreManager.get("default")).thenReturn(blobStore);
        BlobRef blobRef = new BlobRef("default", "blob-abc");
        when(blobStore.store(any(InputStream.class), anyLong(), any())).thenReturn(blobRef);

        var coords = new ComponentCoordinates("com.example", "artifact", "1.0", Map.of());
        when(extractor.extractFromPath(path)).thenReturn(Optional.of(coords));

        var component = createComponent();
        when(componentRepository.findByRepositoryIdAndNamespaceAndNameAndVersion(
                        REPO_ID, "com.example", "artifact", "1.0"))
                .thenReturn(Optional.of(component));

        when(assetRepository.findByRepositoryIdAndPath(REPO_ID, path)).thenReturn(Optional.empty());
        when(assetRepository.save(any(AssetEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        Optional<FormatResponse> result = service.fetchAndCache(repo, path, extractor);

        assertTrue(result.isPresent());
        assertInstanceOf(ContentResponse.class, result.get());

        ContentResponse contentResponse = (ContentResponse) result.get();
        assertEquals("application/java-archive", contentResponse.contentType());
        assertEquals(content.length, contentResponse.contentLength());

        verify(assetRepository).save(any(AssetEntity.class));
    }

    @Test
    void remote404_storesNegativeCache_returns404Error() throws IOException {
        RepositoryConfig repo = createProxyRepo();
        String path = "com/example/missing/1.0/missing-1.0.jar";

        when(negativeCacheService.isEnabled(repo)).thenReturn(true);
        when(negativeCacheService.isNegativelyCached(REPO_ID, path)).thenReturn(false);
        when(negativeCacheService.getNegativeCacheTtl(repo)).thenReturn(1440);

        var remoteResponse = new RemoteHttpClient.RemoteResponse(
                404, InputStream.nullInputStream(), 0, "text/html");
        when(remoteHttpClient.fetch(eq(REMOTE_URL + "/" + path), any())).thenReturn(remoteResponse);

        Optional<FormatResponse> result = service.fetchAndCache(repo, path, extractor);

        assertTrue(result.isPresent());
        assertInstanceOf(ErrorResponse.class, result.get());
        assertEquals(404, ((ErrorResponse) result.get()).statusCode());
        verify(negativeCacheService).cacheNegativeResult(REPO_ID, path, 1440);
    }

    @Test
    void remoteServerError_returnsErrorResponse() throws IOException {
        RepositoryConfig repo = createProxyRepo();
        String path = "com/example/broken/1.0/broken-1.0.jar";

        when(negativeCacheService.isEnabled(repo)).thenReturn(true);
        when(negativeCacheService.isNegativelyCached(REPO_ID, path)).thenReturn(false);

        var remoteResponse = new RemoteHttpClient.RemoteResponse(
                500, InputStream.nullInputStream(), 0, "text/html");
        when(remoteHttpClient.fetch(eq(REMOTE_URL + "/" + path), any())).thenReturn(remoteResponse);

        Optional<FormatResponse> result = service.fetchAndCache(repo, path, extractor);

        assertTrue(result.isPresent());
        assertInstanceOf(ErrorResponse.class, result.get());
        ErrorResponse error = (ErrorResponse) result.get();
        assertEquals(502, error.statusCode());
    }

    @Test
    void negativelyCached_skipsRemoteFetch() throws IOException {
        RepositoryConfig repo = createProxyRepo();
        String path = "com/example/cached-negative/1.0/artifact-1.0.jar";

        when(negativeCacheService.isEnabled(repo)).thenReturn(true);
        when(negativeCacheService.isNegativelyCached(REPO_ID, path)).thenReturn(true);

        Optional<FormatResponse> result = service.fetchAndCache(repo, path, extractor);

        assertTrue(result.isEmpty());
        verify(remoteHttpClient, never()).fetch(anyString(), any());
    }

    @Test
    void negativeCacheDisabled_doesNotCheckCache() throws IOException {
        RepositoryConfig repo = createProxyRepo();
        String path = "com/example/artifact/1.0/artifact-1.0.jar";
        byte[] content = "data".getBytes();

        when(negativeCacheService.isEnabled(repo)).thenReturn(false);

        var remoteResponse = new RemoteHttpClient.RemoteResponse(
                200, new ByteArrayInputStream(content), content.length, "application/octet-stream");
        when(remoteHttpClient.fetch(eq(REMOTE_URL + "/" + path), any())).thenReturn(remoteResponse);

        when(blobStoreManager.get("default")).thenReturn(blobStore);
        BlobRef blobRef = new BlobRef("default", "blob-xyz");
        when(blobStore.store(any(InputStream.class), anyLong(), any())).thenReturn(blobRef);

        when(extractor.extractFromPath(path)).thenReturn(Optional.empty());
        when(assetRepository.findByRepositoryIdAndPath(REPO_ID, path)).thenReturn(Optional.empty());
        when(assetRepository.save(any(AssetEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        Optional<FormatResponse> result = service.fetchAndCache(repo, path, extractor);

        assertTrue(result.isPresent());
        verify(negativeCacheService, never()).isNegativelyCached(any(), anyString());
    }

    @Test
    void upstreamTimeout_returnsErrorWith502() throws IOException {
        RepositoryConfig repo = createProxyRepo();
        String path = "com/example/artifact/1.0/artifact-1.0.jar";

        when(negativeCacheService.isEnabled(repo)).thenReturn(true);
        when(negativeCacheService.isNegativelyCached(REPO_ID, path)).thenReturn(false);

        when(remoteHttpClient.fetch(eq(REMOTE_URL + "/" + path), any()))
                .thenThrow(new UpstreamTimeoutException("Upstream timeout after 2 attempt(s)", null));

        Optional<FormatResponse> result = service.fetchAndCache(repo, path, extractor);

        assertTrue(result.isPresent());
        assertInstanceOf(ErrorResponse.class, result.get());
        ErrorResponse error = (ErrorResponse) result.get();
        assertEquals(502, error.statusCode());
        assertTrue(error.message().contains("timeout"));
    }

    @Test
    void upstream5xx_returns502WithClearMessage() throws IOException {
        RepositoryConfig repo = createProxyRepo();
        String path = "com/example/artifact/1.0/artifact-1.0.jar";

        when(negativeCacheService.isEnabled(repo)).thenReturn(true);
        when(negativeCacheService.isNegativelyCached(REPO_ID, path)).thenReturn(false);

        var remoteResponse = new RemoteHttpClient.RemoteResponse(
                503, InputStream.nullInputStream(), 0, "text/html");
        when(remoteHttpClient.fetch(eq(REMOTE_URL + "/" + path), any())).thenReturn(remoteResponse);

        Optional<FormatResponse> result = service.fetchAndCache(repo, path, extractor);

        assertTrue(result.isPresent());
        assertInstanceOf(ErrorResponse.class, result.get());
        ErrorResponse error = (ErrorResponse) result.get();
        assertEquals(502, error.statusCode());
        assertTrue(error.message().contains("Upstream server error"));
        assertTrue(error.message().contains("503"));
    }

    @Test
    void ioException_returnsErrorResponse() throws IOException {
        RepositoryConfig repo = createProxyRepo();
        String path = "com/example/artifact/1.0/artifact-1.0.jar";

        when(negativeCacheService.isEnabled(repo)).thenReturn(true);
        when(negativeCacheService.isNegativelyCached(REPO_ID, path)).thenReturn(false);

        when(remoteHttpClient.fetch(eq(REMOTE_URL + "/" + path), any()))
                .thenThrow(new IOException("Connection refused"));

        Optional<FormatResponse> result = service.fetchAndCache(repo, path, extractor);

        assertTrue(result.isPresent());
        assertInstanceOf(ErrorResponse.class, result.get());
        ErrorResponse error = (ErrorResponse) result.get();
        assertEquals(502, error.statusCode());
        assertTrue(error.message().contains("Connection refused"));
    }

    @Test
    void nonOkStatusWithNullBody_returns502_doesNotThrowNpe() throws IOException {
        // Reproduces the eurodata report: a forward proxy returns a non-2xx status
        // (e.g. 403/407) with a null response body. The error-handling branch must not
        // call body().close() on null and crash with a 500 NPE.
        RepositoryConfig repo = createProxyRepo();
        String path = "com/example/artifact/1.0/artifact-1.0.jar";

        when(negativeCacheService.isEnabled(repo)).thenReturn(false);

        var remoteResponse = new RemoteHttpClient.RemoteResponse(403, null, 0, "text/html");
        when(remoteHttpClient.fetch(eq(REMOTE_URL + "/" + path), any())).thenReturn(remoteResponse);

        Optional<FormatResponse> result = service.fetchAndCache(repo, path, extractor);

        assertTrue(result.isPresent());
        assertInstanceOf(ErrorResponse.class, result.get());
        assertEquals(502, ((ErrorResponse) result.get()).statusCode());
    }

    @Test
    void status404WithNullBody_returns404_doesNotThrowNpe() throws IOException {
        RepositoryConfig repo = createProxyRepo();
        String path = "com/example/missing/1.0/missing-1.0.jar";

        when(negativeCacheService.isEnabled(repo)).thenReturn(true);
        when(negativeCacheService.isNegativelyCached(REPO_ID, path)).thenReturn(false);
        when(negativeCacheService.getNegativeCacheTtl(repo)).thenReturn(1440);

        var remoteResponse = new RemoteHttpClient.RemoteResponse(404, null, 0, "text/html");
        when(remoteHttpClient.fetch(eq(REMOTE_URL + "/" + path), any())).thenReturn(remoteResponse);

        Optional<FormatResponse> result = service.fetchAndCache(repo, path, extractor);

        assertTrue(result.isPresent());
        assertInstanceOf(ErrorResponse.class, result.get());
        assertEquals(404, ((ErrorResponse) result.get()).statusCode());
        verify(negativeCacheService).cacheNegativeResult(REPO_ID, path, 1440);
    }

    @Test
    void status200WithNullBody_returns502_doesNotThrowNpe() throws IOException {
        RepositoryConfig repo = createProxyRepo();
        String path = "com/example/artifact/1.0/artifact-1.0.jar";

        when(negativeCacheService.isEnabled(repo)).thenReturn(false);

        var remoteResponse = new RemoteHttpClient.RemoteResponse(200, null, -1, "application/octet-stream");
        when(remoteHttpClient.fetch(eq(REMOTE_URL + "/" + path), any())).thenReturn(remoteResponse);

        Optional<FormatResponse> result = service.fetchAndCache(repo, path, extractor);

        assertTrue(result.isPresent());
        assertInstanceOf(ErrorResponse.class, result.get());
        assertEquals(502, ((ErrorResponse) result.get()).statusCode());
        // Must not have attempted to store an empty blob
        verify(assetRepository, never()).save(any(AssetEntity.class));
    }

    @Test
    void getRemoteUrl_stripsTrailingSlash() {
        RepositoryConfig repo = new RepositoryConfig(
                REPO_ID,
                "proxy-repo",
                "maven2",
                RepositoryType.PROXY,
                true,
                "default",
                Map.of("proxy", Map.of("remoteUrl", "https://repo.example.com/maven2/")));

        String url = service.getRemoteUrl(repo);
        assertEquals("https://repo.example.com/maven2", url);
    }

    @Test
    void remote404_negativeCacheDisabled_doesNotStoreEntry() throws IOException {
        RepositoryConfig repo = createProxyRepo();
        String path = "com/example/missing/1.0/missing-1.0.jar";

        when(negativeCacheService.isEnabled(repo)).thenReturn(false);

        var remoteResponse = new RemoteHttpClient.RemoteResponse(
                404, InputStream.nullInputStream(), 0, "text/html");
        when(remoteHttpClient.fetch(eq(REMOTE_URL + "/" + path), any())).thenReturn(remoteResponse);

        Optional<FormatResponse> result = service.fetchAndCache(repo, path, extractor);

        assertTrue(result.isPresent());
        assertInstanceOf(ErrorResponse.class, result.get());
        assertEquals(404, ((ErrorResponse) result.get()).statusCode());
        verify(negativeCacheService, never()).cacheNegativeResult(any(), anyString(), anyInt());
    }

    @Test
    void concurrentRequests_sameArtifact_fetchesFromRemoteOnlyOnce() throws Exception {
        RepositoryConfig repo = createProxyRepo();
        String path = "com/example/artifact/1.0/artifact-1.0.jar";
        byte[] content = "jar-content".getBytes();

        AtomicInteger remoteFetchCount = new AtomicInteger(0);
        CountDownLatch fetchStarted = new CountDownLatch(1);
        CountDownLatch proceedWithFetch = new CountDownLatch(1);

        when(negativeCacheService.isEnabled(repo)).thenReturn(false);

        when(remoteHttpClient.fetch(eq(REMOTE_URL + "/" + path), any())).thenAnswer(invocation -> {
            remoteFetchCount.incrementAndGet();
            // Signal that we've entered the fetch, then block so the second thread
            // arrives at putIfAbsent while this fetch is still in progress
            fetchStarted.countDown();
            proceedWithFetch.await(5, TimeUnit.SECONDS);
            return new RemoteHttpClient.RemoteResponse(
                    200, new ByteArrayInputStream(content), content.length, "application/java-archive");
        });

        when(blobStoreManager.get("default")).thenReturn(blobStore);
        BlobRef blobRef = new BlobRef("default", "blob-abc");
        when(blobStore.store(any(InputStream.class), anyLong(), any())).thenReturn(blobRef);
        when(extractor.extractFromPath(path)).thenReturn(Optional.empty());
        when(assetRepository.findByRepositoryIdAndPath(REPO_ID, path)).thenReturn(Optional.empty());
        when(assetRepository.save(any(AssetEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            // Submit first request - it will enter the fetch and block on proceedWithFetch
            Future<Optional<FormatResponse>> future1 =
                    executor.submit(() -> service.fetchAndCache(repo, path, extractor));

            // Wait until the first request has started the remote fetch (future is in the map)
            assertTrue(fetchStarted.await(5, TimeUnit.SECONDS), "First thread should start fetching");

            // Submit second request - it will find the existing future via putIfAbsent
            Future<Optional<FormatResponse>> future2 =
                    executor.submit(() -> service.fetchAndCache(repo, path, extractor));

            // Release the fetch so both futures complete
            proceedWithFetch.countDown();

            Optional<FormatResponse> result1 = future1.get(10, TimeUnit.SECONDS);
            Optional<FormatResponse> result2 = future2.get(10, TimeUnit.SECONDS);

            assertTrue(result1.isPresent());
            assertTrue(result2.isPresent());
            assertEquals(1, remoteFetchCount.get(), "Remote should be fetched exactly once for concurrent requests");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void flatAuthCredentials_usedForUpstreamAuth() throws IOException {
        RepositoryConfig repo = new RepositoryConfig(
                REPO_ID,
                "proxy-repo",
                "maven2",
                RepositoryType.PROXY,
                true,
                "default",
                Map.of("proxy", Map.of(
                        "remoteUrl", REMOTE_URL,
                        "username", "myuser",
                        "password", "mypass")));

        String path = "com/example/artifact/1.0/artifact-1.0.jar";
        byte[] content = "data".getBytes();

        when(negativeCacheService.isEnabled(repo)).thenReturn(false);

        var remoteResponse = new RemoteHttpClient.RemoteResponse(
                200, new ByteArrayInputStream(content), content.length, "application/octet-stream");
        when(remoteHttpClient.fetchWithAuth(
                        eq(REMOTE_URL + "/" + path), eq("myuser"), eq("mypass"), any()))
                .thenReturn(remoteResponse);

        when(blobStoreManager.get("default")).thenReturn(blobStore);
        BlobRef blobRef = new BlobRef("default", "blob-xyz");
        when(blobStore.store(any(InputStream.class), anyLong(), any())).thenReturn(blobRef);
        when(extractor.extractFromPath(path)).thenReturn(Optional.empty());
        when(assetRepository.findByRepositoryIdAndPath(REPO_ID, path)).thenReturn(Optional.empty());
        when(assetRepository.save(any(AssetEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        Optional<FormatResponse> result = service.fetchAndCache(repo, path, extractor);

        assertTrue(result.isPresent());
        assertInstanceOf(ContentResponse.class, result.get());
        // Verify that fetchWithAuth was called (not plain fetch)
        verify(remoteHttpClient).fetchWithAuth(anyString(), eq("myuser"), eq("mypass"), any());
        verify(remoteHttpClient, never()).fetch(anyString(), any());
    }

    @Test
    void nestedAuthCredentials_stillWork() throws IOException {
        RepositoryConfig repo = new RepositoryConfig(
                REPO_ID,
                "proxy-repo",
                "maven2",
                RepositoryType.PROXY,
                true,
                "default",
                Map.of("proxy", Map.of(
                        "remoteUrl", REMOTE_URL,
                        "authentication", Map.of("username", "nesteduser", "password", "nestedpass"))));

        String path = "com/example/artifact/1.0/artifact-1.0.jar";
        byte[] content = "data".getBytes();

        when(negativeCacheService.isEnabled(repo)).thenReturn(false);

        var remoteResponse = new RemoteHttpClient.RemoteResponse(
                200, new ByteArrayInputStream(content), content.length, "application/octet-stream");
        when(remoteHttpClient.fetchWithAuth(
                        eq(REMOTE_URL + "/" + path), eq("nesteduser"), eq("nestedpass"), any()))
                .thenReturn(remoteResponse);

        when(blobStoreManager.get("default")).thenReturn(blobStore);
        BlobRef blobRef = new BlobRef("default", "blob-xyz");
        when(blobStore.store(any(InputStream.class), anyLong(), any())).thenReturn(blobRef);
        when(extractor.extractFromPath(path)).thenReturn(Optional.empty());
        when(assetRepository.findByRepositoryIdAndPath(REPO_ID, path)).thenReturn(Optional.empty());
        when(assetRepository.save(any(AssetEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        Optional<FormatResponse> result = service.fetchAndCache(repo, path, extractor);

        assertTrue(result.isPresent());
        assertInstanceOf(ContentResponse.class, result.get());
        verify(remoteHttpClient).fetchWithAuth(anyString(), eq("nesteduser"), eq("nestedpass"), any());
    }

    private RepositoryConfig createProxyRepo() {
        return new RepositoryConfig(
                REPO_ID,
                "proxy-repo",
                "maven2",
                RepositoryType.PROXY,
                true,
                "default",
                Map.of("proxy", Map.of("remoteUrl", REMOTE_URL)));
    }

    private ComponentEntity createComponent() {
        var component = new ComponentEntity();
        component.setId(UUID.randomUUID());
        component.setRepositoryId(REPO_ID);
        component.setFormat("maven2");
        component.setNamespace("com.example");
        component.setName("artifact");
        component.setVersion("1.0");
        component.setCreatedAt(Instant.now());
        component.setUpdatedAt(Instant.now());
        return component;
    }
}
