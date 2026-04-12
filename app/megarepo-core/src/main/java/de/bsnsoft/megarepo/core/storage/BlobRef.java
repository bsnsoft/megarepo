package de.bsnsoft.megarepo.core.storage;

public record BlobRef(String blobStoreName, String blobId) {

    public String toExternalForm() {
        return blobStoreName + "@" + blobId;
    }

    public static BlobRef parse(String external) {
        String[] parts = external.split("@", 2);
        return new BlobRef(parts[0], parts[1]);
    }
}
