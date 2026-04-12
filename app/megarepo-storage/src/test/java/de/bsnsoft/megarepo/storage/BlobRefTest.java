package de.bsnsoft.megarepo.storage;

import de.bsnsoft.megarepo.core.storage.BlobRef;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BlobRefTest {

    @Test
    void testToExternalForm() {
        BlobRef ref = new BlobRef("default", "vol-00/chap-000/abc123");
        assertEquals("default@vol-00/chap-000/abc123", ref.toExternalForm());
    }

    @Test
    void testParse() {
        BlobRef ref = BlobRef.parse("default@vol-00/chap-000/abc123");
        assertEquals("default", ref.blobStoreName());
        assertEquals("vol-00/chap-000/abc123", ref.blobId());
    }

    @Test
    void testRoundTrip() {
        BlobRef original = new BlobRef("my-store", "vol-01/chap-042/some-uuid");
        String external = original.toExternalForm();
        BlobRef parsed = BlobRef.parse(external);
        assertEquals(original, parsed);
    }

    @Test
    void testParseWithAtInBlobId() {
        BlobRef ref = BlobRef.parse("store@blob@with@at");
        assertEquals("store", ref.blobStoreName());
        assertEquals("blob@with@at", ref.blobId());
    }
}
