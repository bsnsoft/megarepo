package de.bsnsoft.megarepo.core.format;

import java.util.Map;

public record ComponentCoordinates(
        String namespace,
        String name,
        String version,
        Map<String, String> formatAttributes
) {
}
