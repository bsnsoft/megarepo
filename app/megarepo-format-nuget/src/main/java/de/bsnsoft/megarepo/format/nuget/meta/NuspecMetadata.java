package de.bsnsoft.megarepo.format.nuget.meta;

import java.util.List;

/**
 * Metadata extracted from the {@code .nuspec} manifest inside a {@code .nupkg}.
 *
 * @param id          the package id exactly as declared (original casing)
 * @param version     the version exactly as declared (not yet normalized)
 * @param description package description, may be {@code null}
 * @param authors     comma-separated author list, may be {@code null}
 * @param dependencies flattened dependency list across all target-framework groups
 */
public record NuspecMetadata(
        String id,
        String version,
        String description,
        String authors,
        List<Dependency> dependencies) {

    /**
     * One {@code <dependency>} element.
     *
     * @param targetFramework the surrounding {@code <group targetFramework="...">},
     *                        empty string for ungrouped dependencies
     */
    public record Dependency(String id, String versionRange, String targetFramework) {}
}
