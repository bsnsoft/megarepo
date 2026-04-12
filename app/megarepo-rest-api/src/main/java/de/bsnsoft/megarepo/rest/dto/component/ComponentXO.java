package de.bsnsoft.megarepo.rest.dto.component;

import java.util.List;
import java.util.UUID;

public record ComponentXO(
        UUID id,
        String repository,
        String format,
        String group,
        String name,
        String version,
        List<AssetXO> assets) {}
