package de.bsnsoft.megarepo.storage.s3;

import de.bsnsoft.megarepo.core.storage.Blob;
import de.bsnsoft.megarepo.core.storage.BlobProperties;
import de.bsnsoft.megarepo.core.storage.BlobRef;
import de.bsnsoft.megarepo.core.storage.BlobStoreMetrics;
import de.bsnsoft.megarepo.core.storage.BlobStoreType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class S3BlobStoreTest {

    private S3Client s3Client;
    private S3BlobStore store;
    private S3BlobStoreConfig config;

    @BeforeEach
    void setUp() {
        s3Client = mock(S3Client.class);
        config = new S3BlobStoreConfig("test-bucket", "eu-central-1", "AKID", "SECRET", null, "prefix/");
        store = new S3BlobStore("s3-test", s3Client, config);
    }

    @Test
    void nameAndTypeAreCorrect() {
        assertEquals("s3-test", store.getName());
        assertEquals(BlobStoreType.S3, store.getType());
    }

    @Test
    void storeUploadsBytesAndProperties() {
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        byte[] data = "Hello S3!".getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = Map.of("Content-Type", "text/plain");

        BlobRef ref = store.store(new ByteArrayInputStream(data), headers);

        assertNotNull(ref);
        assertEquals("s3-test", ref.blobStoreName());
        assertNotNull(ref.blobId());

        // Two putObject calls: one for .bytes, one for .properties
        ArgumentCaptor<PutObjectRequest> requestCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client, times(2)).putObject(requestCaptor.capture(), any(RequestBody.class));

        List<PutObjectRequest> requests = requestCaptor.getAllValues();
        PutObjectRequest bytesRequest = requests.get(0);
        PutObjectRequest propsRequest = requests.get(1);

        assertEquals("test-bucket", bytesRequest.bucket());
        assertTrue(bytesRequest.key().startsWith("prefix/"));
        assertTrue(bytesRequest.key().endsWith(".bytes"));
        assertEquals("text/plain", bytesRequest.contentType());

        assertEquals("test-bucket", propsRequest.bucket());
        assertTrue(propsRequest.key().endsWith(".properties"));
        assertEquals("application/json", propsRequest.contentType());
    }

    @Test
    void storeWithSizeCallsDoStore() {
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        byte[] data = "sized data".getBytes(StandardCharsets.UTF_8);
        BlobRef ref = store.store(new ByteArrayInputStream(data), data.length, Map.of());

        assertNotNull(ref);
        verify(s3Client, times(2)).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void getReturnsBlob() throws Exception {
        // Prepare a properties JSON for mock response
        String propsJson = """
                {
                  "size": 9,
                  "contentType": "text/plain",
                  "checksums": {"md5": "abc123"},
                  "createdAt": "2026-01-01T00:00:00Z",
                  "headers": {"Content-Type": "text/plain"}
                }
                """;
        byte[] blobData = "Hello S3!".getBytes(StandardCharsets.UTF_8);

        // Mock properties response
        ResponseInputStream<GetObjectResponse> propsResponse = new ResponseInputStream<>(
                GetObjectResponse.builder().build(),
                AbortableInputStream.create(new ByteArrayInputStream(propsJson.getBytes(StandardCharsets.UTF_8))));

        // Mock bytes response
        ResponseInputStream<GetObjectResponse> bytesResponse = new ResponseInputStream<>(
                GetObjectResponse.builder().build(),
                AbortableInputStream.create(new ByteArrayInputStream(blobData)));

        when(s3Client.getObject(any(GetObjectRequest.class)))
                .thenReturn(propsResponse)
                .thenReturn(bytesResponse);

        BlobRef ref = new BlobRef("s3-test", "test-uuid");
        Optional<Blob> result = store.get(ref);

        assertTrue(result.isPresent());
        try (Blob blob = result.get()) {
            assertArrayEquals(blobData, blob.inputStream().readAllBytes());
            assertEquals("text/plain", blob.properties().contentType());
            assertEquals(9, blob.properties().size());
        }

        // Verify two getObject calls: properties first, then bytes
        verify(s3Client, times(2)).getObject(any(GetObjectRequest.class));
    }

    @Test
    void getReturnsEmptyWhenKeyNotFound() {
        when(s3Client.getObject(any(GetObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().message("Not found").build());

        BlobRef ref = new BlobRef("s3-test", "nonexistent");
        Optional<Blob> result = store.get(ref);

        assertFalse(result.isPresent());
    }

    @Test
    void deleteRemovesBothObjects() {
        // exists() check succeeds
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().build());

        BlobRef ref = new BlobRef("s3-test", "test-uuid");
        boolean result = store.delete(ref);

        assertTrue(result);

        ArgumentCaptor<DeleteObjectRequest> captor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client, times(2)).deleteObject(captor.capture());

        List<DeleteObjectRequest> requests = captor.getAllValues();
        assertTrue(requests.get(0).key().endsWith(".bytes"));
        assertTrue(requests.get(1).key().endsWith(".properties"));
        assertEquals("test-bucket", requests.get(0).bucket());
    }

    @Test
    void deleteReturnsFalseWhenBlobDoesNotExist() {
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().message("Not found").build());

        BlobRef ref = new BlobRef("s3-test", "nonexistent");
        boolean result = store.delete(ref);

        assertFalse(result);
        verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void existsReturnsTrueWhenHeadSucceeds() {
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().build());

        BlobRef ref = new BlobRef("s3-test", "test-uuid");
        assertTrue(store.exists(ref));

        ArgumentCaptor<HeadObjectRequest> captor = ArgumentCaptor.forClass(HeadObjectRequest.class);
        verify(s3Client).headObject(captor.capture());
        assertEquals("test-bucket", captor.getValue().bucket());
        assertEquals("prefix/test-uuid.bytes", captor.getValue().key());
    }

    @Test
    void existsReturnsFalseOnNoSuchKey() {
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().message("Not found").build());

        BlobRef ref = new BlobRef("s3-test", "test-uuid");
        assertFalse(store.exists(ref));
    }

    @Test
    void getMetricsCountsBytesObjects() {
        S3Object bytesObj1 = S3Object.builder().key("prefix/uuid1.bytes").size(100L).build();
        S3Object propsObj1 = S3Object.builder().key("prefix/uuid1.properties").size(50L).build();
        S3Object bytesObj2 = S3Object.builder().key("prefix/uuid2.bytes").size(200L).build();
        S3Object propsObj2 = S3Object.builder().key("prefix/uuid2.properties").size(60L).build();

        ListObjectsV2Response response = ListObjectsV2Response.builder()
                .contents(bytesObj1, propsObj1, bytesObj2, propsObj2)
                .isTruncated(false)
                .build();

        when(s3Client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(response);

        BlobStoreMetrics metrics = store.getMetrics();

        assertEquals(2, metrics.blobCount());
        assertEquals(300, metrics.totalSizeBytes());
        // S3 has no available space concept
        assertEquals(null, metrics.availableSpaceBytes());
    }

    @Test
    void getMetricsHandlesPagination() {
        S3Object obj1 = S3Object.builder().key("prefix/uuid1.bytes").size(100L).build();
        S3Object obj2 = S3Object.builder().key("prefix/uuid2.bytes").size(200L).build();

        ListObjectsV2Response page1 = ListObjectsV2Response.builder()
                .contents(obj1)
                .isTruncated(true)
                .nextContinuationToken("token123")
                .build();

        ListObjectsV2Response page2 = ListObjectsV2Response.builder()
                .contents(obj2)
                .isTruncated(false)
                .build();

        when(s3Client.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(page1)
                .thenReturn(page2);

        BlobStoreMetrics metrics = store.getMetrics();

        assertEquals(2, metrics.blobCount());
        assertEquals(300, metrics.totalSizeBytes());
    }

    @Test
    void compactIsNoOp() {
        store.compact();
        // Should not interact with S3 at all
        verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void storeDefaultsContentTypeToOctetStream() {
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        byte[] data = "binary data".getBytes(StandardCharsets.UTF_8);
        store.store(new ByteArrayInputStream(data), Map.of());

        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client, times(2)).putObject(captor.capture(), any(RequestBody.class));

        PutObjectRequest bytesRequest = captor.getAllValues().get(0);
        assertEquals("application/octet-stream", bytesRequest.contentType());
    }
}
