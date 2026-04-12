package de.bsnsoft.megarepo.core.format;

import java.util.List;

public record UploadDefinition(
        String format,
        boolean multipleUpload,
        List<UploadFieldDefinition> componentFields,
        List<UploadFieldDefinition> assetFields
) {

    public record UploadFieldDefinition(
            String name,
            String type,
            String description,
            boolean optional,
            String group
    ) {
    }
}
