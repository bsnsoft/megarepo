package de.bsnsoft.megarepo.rest.dto.cleanup;

import de.bsnsoft.megarepo.rest.dto.component.AssetXO;

import java.util.List;

public record CleanupPreviewResponse(List<AssetXO> assetsToDelete, long totalSize, int count) {}
