package de.bsnsoft.megarepo.database.repository;

import de.bsnsoft.megarepo.core.firewall.FirewallFactsState;
import de.bsnsoft.megarepo.database.entity.FirewallComponentFactsEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

@Repository
public interface FirewallComponentFactsJpaRepository
        extends JpaRepository<FirewallComponentFactsEntity, String> {

    /** Batch read for an evaluation that looks at several components at once. */
    List<FirewallComponentFactsEntity> findByPurlIn(Collection<String> purls);

    /**
     * The resolver's work list: rows nobody has answered yet, oldest first.
     *
     * <p>Both unsettled states, not only {@code PENDING}: a row created by a
     * request-path miss starts {@code UNKNOWN}, and a {@code PENDING} row whose
     * resolver died in a restart would otherwise never be picked up again.
     */
    @Query("""
            SELECT f FROM FirewallComponentFactsEntity f
            WHERE f.state IN :states
            ORDER BY f.createdAt ASC
            """)
    List<FirewallComponentFactsEntity> findUnresolved(
            @Param("states") Collection<FirewallFactsState> states, Pageable pageable);

    long countByState(FirewallFactsState state);

    /**
     * Settled rows old enough to be worth asking about again.
     *
     * <p>Publication dates never change, but declared licenses are re-published
     * and an {@code UNAVAILABLE} verdict can be the result of an outage rather
     * than of the ecosystem. Re-resolution is therefore a slow background
     * refresh, not a cache expiry that could make the request path wait.
     */
    @Query("""
            SELECT f FROM FirewallComponentFactsEntity f
            WHERE f.state = :state
              AND (f.fetchedAt IS NULL OR f.fetchedAt <= :staleBefore)
            ORDER BY f.fetchedAt ASC NULLS FIRST
            """)
    List<FirewallComponentFactsEntity> findStale(
            @Param("state") FirewallFactsState state,
            @Param("staleBefore") Instant staleBefore,
            Pageable pageable);
}
