package de.bsnsoft.megarepo.search;

import java.util.List;

/**
 * Paginated search result with continuation-token support.
 *
 * @param items             the result items for the current page
 * @param continuationToken token for fetching the next page, or null if no more pages
 * @param totalCount        total number of matching items across all pages
 * @param <T>               the item type
 */
public record SearchResult<T>(List<T> items, String continuationToken, long totalCount) {}
