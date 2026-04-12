package de.bsnsoft.megarepo.search;

import de.bsnsoft.megarepo.database.entity.ComponentEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SearchServiceTest {

    @Mock
    private EntityManager entityManager;

    @Mock
    private CriteriaBuilder criteriaBuilder;

    @Mock
    private CriteriaQuery<ComponentEntity> componentCriteriaQuery;

    @Mock
    private CriteriaQuery<Long> countCriteriaQuery;

    @Mock
    private Root<ComponentEntity> componentRoot;

    @Mock
    private TypedQuery<ComponentEntity> componentTypedQuery;

    @Mock
    private TypedQuery<Long> countTypedQuery;

    @Mock
    private Path<Object> objectPath;

    @Mock
    private Expression<String> stringExpression;

    @Mock
    private Expression<Long> longExpression;

    @Mock
    private Predicate predicate;

    @Mock
    private Order order;

    private SearchService searchService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        searchService = new SearchService(entityManager);

        lenient().when(entityManager.getCriteriaBuilder()).thenReturn(criteriaBuilder);

        // Component query setup
        lenient().when(criteriaBuilder.createQuery(ComponentEntity.class)).thenReturn(componentCriteriaQuery);
        lenient().when(componentCriteriaQuery.from(ComponentEntity.class)).thenReturn(componentRoot);
        lenient().when(componentCriteriaQuery.where(any(Predicate[].class))).thenReturn(componentCriteriaQuery);
        lenient().when(componentCriteriaQuery.orderBy(any(Order.class), any(Order.class))).thenReturn(componentCriteriaQuery);
        lenient().when(entityManager.createQuery(componentCriteriaQuery)).thenReturn(componentTypedQuery);
        lenient().when(componentTypedQuery.setFirstResult(anyInt())).thenReturn(componentTypedQuery);
        lenient().when(componentTypedQuery.setMaxResults(anyInt())).thenReturn(componentTypedQuery);

        // Count query setup
        lenient().when(criteriaBuilder.createQuery(Long.class)).thenReturn(countCriteriaQuery);
        lenient().when(countCriteriaQuery.from(ComponentEntity.class)).thenReturn(componentRoot);
        lenient().when(countCriteriaQuery.select(any())).thenReturn(countCriteriaQuery);
        lenient().when(countCriteriaQuery.where(any(Predicate[].class))).thenReturn(countCriteriaQuery);
        lenient().when(entityManager.createQuery(countCriteriaQuery)).thenReturn(countTypedQuery);
        lenient().when(countTypedQuery.getSingleResult()).thenReturn(0L);

        // Path/expression stubs
        lenient().when(componentRoot.get(anyString())).thenReturn(objectPath);
        lenient().when(criteriaBuilder.lower(any())).thenReturn(stringExpression);
        lenient().when(criteriaBuilder.like(any(Expression.class), anyString())).thenReturn(predicate);
        lenient().when(criteriaBuilder.equal(any(), any())).thenReturn(predicate);
        lenient().when(criteriaBuilder.or(any(Predicate[].class))).thenReturn(predicate);
        lenient().when(criteriaBuilder.and(any(Predicate[].class))).thenReturn(predicate);
        lenient().when(criteriaBuilder.count(any())).thenReturn(longExpression);
        lenient().when(criteriaBuilder.asc(any())).thenReturn(order);
    }

    @Test
    void keywordSearchReturnsMatchingComponents() {
        var component = new ComponentEntity();
        component.setName("spring-core");
        component.setFormat("maven2");
        when(componentTypedQuery.getResultList()).thenReturn(List.of(component));
        when(countTypedQuery.getSingleResult()).thenReturn(1L);

        var query = new SearchQuery("spring", null, null, null, null, null, 50, null);
        SearchResult<ComponentEntity> result = searchService.searchComponents(query);

        assertEquals(1, result.items().size());
        assertEquals("spring-core", result.items().getFirst().getName());
        assertEquals(1L, result.totalCount());
        assertNull(result.continuationToken());
    }

    @Test
    void filterByFormatAppliesPredicate() {
        when(componentTypedQuery.getResultList()).thenReturn(Collections.emptyList());
        when(countTypedQuery.getSingleResult()).thenReturn(0L);

        var query = new SearchQuery(null, null, "maven2", null, null, null, 50, null);
        SearchResult<ComponentEntity> result = searchService.searchComponents(query);

        assertEquals(0, result.items().size());
        assertEquals(0L, result.totalCount());
        // Verify format equality predicate was built (called in both count + data query)
        verify(criteriaBuilder, atLeastOnce()).equal(any(), eq("maven2"));
    }

    @Test
    void emptyResultReturnsEmptyList() {
        when(componentTypedQuery.getResultList()).thenReturn(Collections.emptyList());
        when(countTypedQuery.getSingleResult()).thenReturn(0L);

        var query = new SearchQuery("nonexistent", null, null, null, null, null, 50, null);
        SearchResult<ComponentEntity> result = searchService.searchComponents(query);

        assertTrue(result.items().isEmpty());
        assertNull(result.continuationToken());
        assertEquals(0L, result.totalCount());
    }

    @Test
    void paginationWithContinuationToken() {
        when(componentTypedQuery.getResultList()).thenReturn(Collections.emptyList());
        when(countTypedQuery.getSingleResult()).thenReturn(100L);

        String token = Base64.getEncoder().encodeToString("50".getBytes(StandardCharsets.UTF_8));
        var query = new SearchQuery("spring", null, null, null, null, null, 50, token);
        searchService.searchComponents(query);

        // Verify offset was set to 50
        verify(componentTypedQuery).setFirstResult(50);
        verify(componentTypedQuery).setMaxResults(51); // pageSize + 1 for next-page detection
    }

    @Test
    void hasMorePagesSetsNextToken() {
        // Return pageSize + 1 items to indicate there are more pages
        List<ComponentEntity> items = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            var component = new ComponentEntity();
            component.setName("component-" + i);
            items.add(component);
        }
        when(componentTypedQuery.getResultList()).thenReturn(items);
        when(countTypedQuery.getSingleResult()).thenReturn(100L);

        var query = new SearchQuery("component", null, null, null, null, null, 50, null);
        SearchResult<ComponentEntity> result = searchService.searchComponents(query);

        assertEquals(50, result.items().size());
        assertNotNull(result.continuationToken());
        // Decode token and verify it represents offset 50
        String decoded = new String(
                Base64.getDecoder().decode(result.continuationToken()), StandardCharsets.UTF_8);
        assertEquals("50", decoded);
    }

    @Test
    void combinedKeywordAndFormatFilter() {
        var component = new ComponentEntity();
        component.setName("spring-core");
        component.setFormat("maven2");
        when(componentTypedQuery.getResultList()).thenReturn(List.of(component));
        when(countTypedQuery.getSingleResult()).thenReturn(1L);

        var query = new SearchQuery("spring", null, "maven2", null, null, null, 50, null);
        SearchResult<ComponentEntity> result = searchService.searchComponents(query);

        assertEquals(1, result.items().size());
        // Verify both keyword and format predicates were built (called in both count + data query)
        verify(criteriaBuilder, atLeastOnce()).or(any(Predicate.class), any(Predicate.class));
        verify(criteriaBuilder, atLeastOnce()).equal(any(), eq("maven2"));
    }

    @Test
    void invalidContinuationTokenDefaultsToZero() {
        assertEquals(0, SearchService.decodeOffset("not-base64!!!"));
        assertEquals(0, SearchService.decodeOffset(null));
        assertEquals(0, SearchService.decodeOffset(""));
        assertEquals(0, SearchService.decodeOffset("   "));
    }

    @Test
    void encodeDecodeOffsetRoundTrips() {
        String token = SearchService.encodeOffset(42);
        assertEquals(42, SearchService.decodeOffset(token));
    }

    @Test
    void countComponentsDelegatesToCriteriaQuery() {
        when(countTypedQuery.getSingleResult()).thenReturn(7L);

        var query = new SearchQuery(null, null, "maven2", null, null, null, 50, null);
        long count = searchService.countComponents(query);

        assertEquals(7L, count);
    }
}
