package de.bsnsoft.megarepo.search;

import de.bsnsoft.megarepo.database.entity.AssetEntity;
import de.bsnsoft.megarepo.database.entity.ComponentEntity;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Search index maintenance. For MVP this is a no-op since we rely on direct
 * PostgreSQL queries with pg_trgm GIN indexes. A future implementation could
 * maintain a search-optimized table or integrate Elasticsearch.
 */
@Component
public class SearchIndexer {

    /**
     * Index a component for search. Currently a no-op.
     */
    public void index(ComponentEntity component) {
        // No-op: pg_trgm indexes on the components table handle search
    }

    /**
     * Index an asset for search. Currently a no-op.
     */
    public void indexAsset(AssetEntity asset) {
        // No-op: pg_trgm indexes on the assets table handle search
    }

    /**
     * Remove a component from the search index. Currently a no-op.
     */
    public void remove(UUID componentId) {
        // No-op: row deletion from components table is sufficient
    }
}
