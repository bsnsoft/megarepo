package de.bsnsoft.megarepo.search;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SearchQueryTest {

    @Test
    void defaultPageSizeIs50WhenZero() {
        var query = new SearchQuery("spring", null, null, null, null, null, 0, null);
        assertEquals(50, query.pageSize());
    }

    @Test
    void negativePageSizeBecomesDefault() {
        var query = new SearchQuery("spring", null, null, null, null, null, -10, null);
        assertEquals(50, query.pageSize());
    }

    @Test
    void pageSizeCappedAt200() {
        var query = new SearchQuery("spring", null, null, null, null, null, 500, null);
        assertEquals(200, query.pageSize());
    }

    @Test
    void continuationTokenPreserved() {
        var query = new SearchQuery("spring", null, null, null, null, null, 50, "abc123");
        assertEquals("abc123", query.continuationToken());
    }

    @Test
    void validPageSizeUnchanged() {
        var query = new SearchQuery("spring", null, null, null, null, null, 100, null);
        assertEquals(100, query.pageSize());
    }

    @Test
    void allFieldsPreserved() {
        var query = new SearchQuery("spring", "maven-central", "maven2", "org.springframework", "spring-core", "6.1.0", 25, null);
        assertEquals("spring", query.keyword());
        assertEquals("maven-central", query.repository());
        assertEquals("maven2", query.format());
        assertEquals("org.springframework", query.namespace());
        assertEquals("spring-core", query.name());
        assertEquals("6.1.0", query.version());
        assertEquals(25, query.pageSize());
        assertNull(query.continuationToken());
    }
}
