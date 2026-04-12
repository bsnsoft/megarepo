package de.bsnsoft.megarepo.search;

/**
 * Immutable query parameters for searching components and assets.
 *
 * @param keyword            free-text search term (matches name, namespace, path)
 * @param repository         filter by repository name (optional)
 * @param format             filter by format (optional)
 * @param namespace          filter by groupId/scope (optional)
 * @param name               filter by exact name (optional)
 * @param version            filter by version (optional)
 * @param pageSize           page size, default 50, max 200
 * @param continuationToken  base64-encoded offset for pagination
 */
public record SearchQuery(
        String keyword,
        String repository,
        String format,
        String namespace,
        String name,
        String version,
        int pageSize,
        String continuationToken) {

    public SearchQuery {
        if (pageSize <= 0) pageSize = 50;
        if (pageSize > 200) pageSize = 200;
    }
}
