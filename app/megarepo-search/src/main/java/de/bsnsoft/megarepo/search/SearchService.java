package de.bsnsoft.megarepo.search;

import de.bsnsoft.megarepo.database.entity.AssetEntity;
import de.bsnsoft.megarepo.database.entity.ComponentEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Full-text search service backed by PostgreSQL with pg_trgm.
 * Uses JPA Criteria API for type-safe, composable queries.
 */
@Service
public class SearchService {

    private final EntityManager entityManager;

    public SearchService(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /**
     * Search components by keyword and optional filters.
     * Keyword matches against name and namespace using case-insensitive LIKE
     * (made efficient by pg_trgm GIN indexes on the database).
     */
    public SearchResult<ComponentEntity> searchComponents(SearchQuery query) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();

        // Count query
        long totalCount = countComponents(query);

        // Data query
        CriteriaQuery<ComponentEntity> cq = cb.createQuery(ComponentEntity.class);
        Root<ComponentEntity> root = cq.from(ComponentEntity.class);

        List<Predicate> predicates = buildComponentPredicates(cb, root, query);
        if (!predicates.isEmpty()) {
            cq.where(predicates.toArray(new Predicate[0]));
        }
        cq.orderBy(cb.asc(root.get("name")), cb.asc(root.get("version")));

        TypedQuery<ComponentEntity> typedQuery = entityManager.createQuery(cq);
        int offset = decodeOffset(query.continuationToken());
        typedQuery.setFirstResult(offset);
        typedQuery.setMaxResults(query.pageSize() + 1);

        List<ComponentEntity> results = typedQuery.getResultList();
        boolean hasMore = results.size() > query.pageSize();

        List<ComponentEntity> items = hasMore ? results.subList(0, query.pageSize()) : results;
        String nextToken = hasMore ? encodeOffset(offset + query.pageSize()) : null;

        return new SearchResult<>(items, nextToken, totalCount);
    }

    /**
     * Search assets by keyword (matched against path) and optional filters.
     */
    public SearchResult<AssetEntity> searchAssets(SearchQuery query) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();

        // Count query
        long totalCount = countAssets(query);

        // Data query
        CriteriaQuery<AssetEntity> cq = cb.createQuery(AssetEntity.class);
        Root<AssetEntity> root = cq.from(AssetEntity.class);

        List<Predicate> predicates = buildAssetPredicates(cb, root, query);
        if (!predicates.isEmpty()) {
            cq.where(predicates.toArray(new Predicate[0]));
        }
        cq.orderBy(cb.asc(root.get("path")));

        TypedQuery<AssetEntity> typedQuery = entityManager.createQuery(cq);
        int offset = decodeOffset(query.continuationToken());
        typedQuery.setFirstResult(offset);
        typedQuery.setMaxResults(query.pageSize() + 1);

        List<AssetEntity> results = typedQuery.getResultList();
        boolean hasMore = results.size() > query.pageSize();

        List<AssetEntity> items = hasMore ? results.subList(0, query.pageSize()) : results;
        String nextToken = hasMore ? encodeOffset(offset + query.pageSize()) : null;

        return new SearchResult<>(items, nextToken, totalCount);
    }

    /**
     * Count the total number of components matching the query.
     */
    public long countComponents(SearchQuery query) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<ComponentEntity> root = cq.from(ComponentEntity.class);
        cq.select(cb.count(root));

        List<Predicate> predicates = buildComponentPredicates(cb, root, query);
        if (!predicates.isEmpty()) {
            cq.where(predicates.toArray(new Predicate[0]));
        }

        return entityManager.createQuery(cq).getSingleResult();
    }

    private List<Predicate> buildComponentPredicates(
            CriteriaBuilder cb, Root<ComponentEntity> root, SearchQuery query) {

        List<Predicate> predicates = new ArrayList<>();

        if (hasValue(query.keyword())) {
            String escaped = escapeLikePattern(query.keyword().toLowerCase());
            String pattern = "%" + escaped + "%";
            Predicate nameLike = cb.like(cb.lower(root.get("name")), pattern);
            Predicate namespaceLike = cb.like(cb.lower(root.get("namespace")), pattern);
            predicates.add(cb.or(nameLike, namespaceLike));
        }

        if (hasValue(query.format())) {
            predicates.add(cb.equal(root.get("format"), query.format()));
        }

        if (hasValue(query.namespace())) {
            predicates.add(cb.equal(root.get("namespace"), query.namespace()));
        }

        if (hasValue(query.name())) {
            predicates.add(cb.equal(root.get("name"), query.name()));
        }

        if (hasValue(query.version())) {
            predicates.add(cb.equal(root.get("version"), query.version()));
        }

        if (hasValue(query.repository())) {
            // Filter by repository name via a subquery on repository_id
            var subquery = cb.createQuery(ComponentEntity.class).subquery(java.util.UUID.class);
            var repoRoot =
                    subquery.from(de.bsnsoft.megarepo.database.entity.RepositoryEntity.class);
            subquery.select(repoRoot.get("id"));
            subquery.where(cb.equal(repoRoot.get("name"), query.repository()));
            predicates.add(root.get("repositoryId").in(subquery));
        }

        return predicates;
    }

    private List<Predicate> buildAssetPredicates(
            CriteriaBuilder cb, Root<AssetEntity> root, SearchQuery query) {

        List<Predicate> predicates = new ArrayList<>();

        if (hasValue(query.keyword())) {
            String escaped = escapeLikePattern(query.keyword().toLowerCase());
            String pattern = "%" + escaped + "%";
            predicates.add(cb.like(cb.lower(root.get("path")), pattern));
        }

        if (hasValue(query.format())) {
            predicates.add(cb.equal(root.get("format"), query.format()));
        }

        if (hasValue(query.repository())) {
            var subquery = cb.createQuery(AssetEntity.class).subquery(java.util.UUID.class);
            var repoRoot =
                    subquery.from(de.bsnsoft.megarepo.database.entity.RepositoryEntity.class);
            subquery.select(repoRoot.get("id"));
            subquery.where(cb.equal(repoRoot.get("name"), query.repository()));
            predicates.add(root.get("repositoryId").in(subquery));
        }

        return predicates;
    }

    private long countAssets(SearchQuery query) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<AssetEntity> root = cq.from(AssetEntity.class);
        cq.select(cb.count(root));

        List<Predicate> predicates = buildAssetPredicates(cb, root, query);
        if (!predicates.isEmpty()) {
            cq.where(predicates.toArray(new Predicate[0]));
        }

        return entityManager.createQuery(cq).getSingleResult();
    }

    static int decodeOffset(String continuationToken) {
        if (continuationToken == null || continuationToken.isBlank()) {
            return 0;
        }
        try {
            String decoded =
                    new String(Base64.getDecoder().decode(continuationToken), StandardCharsets.UTF_8);
            return Integer.parseInt(decoded);
        } catch (IllegalArgumentException e) {
            return 0;
        }
    }

    static String encodeOffset(int offset) {
        return Base64.getEncoder()
                .encodeToString(String.valueOf(offset).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Escapes SQL LIKE special characters (%, _) in user-provided search terms
     * to prevent users from manipulating query patterns.
     */
    private static String escapeLikePattern(String input) {
        return input.replace("%", "\\%").replace("_", "\\_");
    }

    private static boolean hasValue(String value) {
        return value != null && !value.isBlank();
    }
}
