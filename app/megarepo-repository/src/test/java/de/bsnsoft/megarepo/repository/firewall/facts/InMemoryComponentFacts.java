package de.bsnsoft.megarepo.repository.firewall.facts;

import de.bsnsoft.megarepo.core.firewall.FirewallFactsState;
import de.bsnsoft.megarepo.database.entity.FirewallComponentFactsEntity;
import de.bsnsoft.megarepo.database.repository.FirewallComponentFactsJpaRepository;
import org.mockito.stubbing.Answer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A {@link FirewallComponentFactsJpaRepository} backed by a map.
 *
 * <p>The store's behaviour is about states and transitions, not about SQL: the
 * migration and the entity mapping are already asserted by the contract commit's
 * {@code FirewallMigrationTest} and {@code FirewallPhase2EntityMappingTest}.
 * Spending a Testcontainers context per assertion here would slow the build
 * without testing anything those two do not.
 */
final class InMemoryComponentFacts {

    private final Map<String, FirewallComponentFactsEntity> rows = new LinkedHashMap<>();
    private final FirewallComponentFactsJpaRepository repository =
            mock(FirewallComponentFactsJpaRepository.class);

    InMemoryComponentFacts() {
        when(repository.findById(anyString()))
                .thenAnswer((Answer<Optional<FirewallComponentFactsEntity>>) invocation ->
                        Optional.ofNullable(rows.get(invocation.<String>getArgument(0))));

        when(repository.save(any(FirewallComponentFactsEntity.class)))
                .thenAnswer((Answer<FirewallComponentFactsEntity>) invocation -> {
                    FirewallComponentFactsEntity entity = invocation.getArgument(0);
                    rows.put(entity.getPurl(), entity);
                    return entity;
                });

        when(repository.findByPurlIn(any()))
                .thenAnswer((Answer<List<FirewallComponentFactsEntity>>) invocation -> {
                    Collection<String> purls = invocation.getArgument(0);
                    List<FirewallComponentFactsEntity> found = new ArrayList<>();
                    purls.forEach(purl -> {
                        FirewallComponentFactsEntity row = rows.get(purl);
                        if (row != null) {
                            found.add(row);
                        }
                    });
                    return found;
                });

        when(repository.findUnresolved(any(), any()))
                .thenAnswer((Answer<List<FirewallComponentFactsEntity>>) invocation -> {
                    Collection<FirewallFactsState> states = invocation.getArgument(0);
                    org.springframework.data.domain.Pageable pageable = invocation.getArgument(1);
                    return rows.values().stream()
                            .filter(row -> states.contains(row.getState()))
                            .sorted(Comparator.comparing(FirewallComponentFactsEntity::getCreatedAt))
                            .limit(pageable.getPageSize())
                            .toList();
                });

        when(repository.findStale(any(), any(), any()))
                .thenAnswer((Answer<List<FirewallComponentFactsEntity>>) invocation -> {
                    FirewallFactsState state = invocation.getArgument(0);
                    Instant staleBefore = invocation.getArgument(1);
                    org.springframework.data.domain.Pageable pageable = invocation.getArgument(2);
                    return rows.values().stream()
                            .filter(row -> row.getState() == state)
                            .filter(row -> row.getFetchedAt() == null
                                    || !row.getFetchedAt().isAfter(staleBefore))
                            .limit(pageable.getPageSize())
                            .toList();
                });
    }

    FirewallComponentFactsJpaRepository repository() {
        return repository;
    }

    Optional<FirewallComponentFactsEntity> row(String purl) {
        return Optional.ofNullable(rows.get(purl));
    }

    int size() {
        return rows.size();
    }

    /** Seeds a row directly, bypassing the service, the way a previous sweep would have left it. */
    FirewallComponentFactsEntity given(
            String purl, String purlType, FirewallFactsState state) {
        FirewallComponentFactsEntity entity = new FirewallComponentFactsEntity();
        entity.setPurl(purl);
        entity.setPurlType(purlType);
        entity.setState(state);
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        rows.put(purl, entity);
        return entity;
    }
}
