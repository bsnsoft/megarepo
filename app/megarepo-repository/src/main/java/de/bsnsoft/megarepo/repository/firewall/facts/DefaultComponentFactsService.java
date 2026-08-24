package de.bsnsoft.megarepo.repository.firewall.facts;

import com.github.packageurl.PackageURL;
import de.bsnsoft.megarepo.core.firewall.FirewallFactsState;
import de.bsnsoft.megarepo.database.entity.FirewallComponentFactsEntity;
import de.bsnsoft.megarepo.database.repository.FirewallComponentFactsJpaRepository;
import de.bsnsoft.megarepo.repository.firewall.identity.ComponentIdentity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The request path's read of {@code firewall_component_facts}.
 *
 * <h2>This class never fetches</h2>
 *
 * {@link #lookup} does one indexed primary-key read and answers with whatever it
 * finds, including {@link ComponentFacts#unknown}. There is no "just this once"
 * branch that reads a POM, and there must never be one: the customer's
 * constraint is a 20 ms budget for a cache hit and no outbound traffic from a
 * request thread, and a source call hidden behind a cache miss turns the first
 * download of every new package into a request blocked on a third-party
 * registry.
 *
 * <p>What a miss does instead is leave a placeholder row in state
 * {@link FirewallFactsState#UNKNOWN}. That row is the work item: the background
 * resolver drains it, and the V19 sweep picks up whatever a restart lost. The
 * rule that asked reports {@code INDETERMINATE}, the engine applies the
 * repository's fail mode, and the next request — or the quarantine
 * re-evaluation — sees a settled row.
 *
 * <h2>And never throws</h2>
 *
 * A facts table that is unreachable is a firewall that cannot judge age or
 * license. That is an {@code INDETERMINATE} for those two rules and nothing at
 * all for the other five, which is a far better failure than a 500 on a
 * download. Every public method here catches {@link RuntimeException} and
 * degrades to "unknown".
 */
@Service
public class DefaultComponentFactsService implements ComponentFactsService {

    private static final Logger log = LoggerFactory.getLogger(DefaultComponentFactsService.class);

    private final FirewallComponentFactsJpaRepository factsRepository;
    private final ComponentFactsResolver resolver;

    public DefaultComponentFactsService(
            FirewallComponentFactsJpaRepository factsRepository, ComponentFactsResolver resolver) {
        this.factsRepository = factsRepository;
        this.resolver = resolver;
    }

    @Override
    public ComponentFacts lookup(ComponentIdentity identity) {
        Optional<PackageURL> purl = purlOf(identity);
        if (purl.isEmpty()) {
            // A hash or an unidentified component has no ecosystem to ask. Its
            // key is still the honest answer to "what is this record about".
            return ComponentFacts.unknown(identity == null ? null : identity.key());
        }
        String coordinates = purl.get().getCoordinates();
        try {
            return factsRepository
                    .findById(coordinates)
                    .map(DefaultComponentFactsService::toFacts)
                    .orElseGet(() -> {
                        ensureRow(purl.get());
                        return ComponentFacts.unknown(coordinates);
                    });
        } catch (RuntimeException e) {
            log.warn("Component facts lookup failed for {} — answering UNKNOWN: {}",
                    coordinates, e.toString());
            return ComponentFacts.unknown(coordinates);
        }
    }

    @Override
    public Map<String, ComponentFacts> lookupAll(Collection<ComponentIdentity> identities) {
        Map<String, ComponentFacts> answer = new LinkedHashMap<>();
        if (identities == null || identities.isEmpty()) {
            return answer;
        }

        // identity.key() carries qualifiers, the facts row does not: a sources jar
        // and the main jar are two identities and one published package version.
        Map<String, PackageURL> byIdentityKey = new LinkedHashMap<>();
        for (ComponentIdentity identity : identities) {
            if (identity == null) {
                continue;
            }
            Optional<PackageURL> purl = purlOf(identity);
            if (purl.isEmpty()) {
                answer.put(identity.key(), ComponentFacts.unknown(identity.key()));
            } else {
                byIdentityKey.put(identity.key(), purl.get());
            }
        }
        if (byIdentityKey.isEmpty()) {
            return answer;
        }

        Set<String> coordinates = new LinkedHashSet<>();
        byIdentityKey.values().forEach(purl -> coordinates.add(purl.getCoordinates()));

        Map<String, ComponentFacts> byCoordinates = new HashMap<>();
        try {
            for (FirewallComponentFactsEntity entity : factsRepository.findByPurlIn(coordinates)) {
                byCoordinates.put(entity.getPurl(), toFacts(entity));
            }
        } catch (RuntimeException e) {
            log.warn("Component facts batch lookup failed for {} coordinate(s) — answering UNKNOWN: {}",
                    coordinates.size(), e.toString());
        }

        List<PackageURL> misses = new ArrayList<>();
        byIdentityKey.forEach((identityKey, purl) -> {
            ComponentFacts facts = byCoordinates.get(purl.getCoordinates());
            if (facts == null) {
                misses.add(purl);
                facts = ComponentFacts.unknown(purl.getCoordinates());
            }
            // "one entry per requested identity key, never partial" — a caller
            // that has to null-check a facts map will eventually forget to.
            answer.put(identityKey, facts);
        });
        misses.forEach(this::ensureRow);
        return answer;
    }

    @Override
    public void requestResolution(ComponentIdentity identity) {
        Optional<PackageURL> purl = purlOf(identity);
        if (purl.isEmpty()) {
            return;
        }
        try {
            FirewallComponentFactsEntity row = ensureRow(purl.get());
            if (row != null && row.getState() != null && row.getState().isSettled()) {
                // Settled rows are refreshed by the resolver's own staleness sweep,
                // not by a download. Otherwise every request for a package resolved
                // three years ago would queue work that changes nothing.
                return;
            }
            resolver.enqueue(purl.get().getCoordinates());
        } catch (RuntimeException e) {
            // Enqueueing is best effort by construction: the row exists (or the
            // next request creates it) and the V19 sweep finds it either way.
            log.debug("Could not queue a facts resolution for {}: {}",
                    purl.get().getCoordinates(), e.toString());
        }
    }

    /**
     * Creates the placeholder row for a component nobody has asked about yet.
     *
     * <p>Returns the row that is now in the table — the existing one when another
     * request thread won the race. The unique violation is expected traffic, not
     * an error: two parallel downloads of the same new package is the normal
     * case, and the loser's answer is identical to the winner's.
     */
    private FirewallComponentFactsEntity ensureRow(PackageURL purl) {
        String coordinates = purl.getCoordinates();
        try {
            Optional<FirewallComponentFactsEntity> existing = factsRepository.findById(coordinates);
            if (existing.isPresent()) {
                return existing.get();
            }
            FirewallComponentFactsEntity entity = new FirewallComponentFactsEntity();
            entity.setPurl(coordinates);
            entity.setPurlType(purl.getType());
            entity.setState(FirewallFactsState.UNKNOWN);
            entity.setCreatedAt(Instant.now());
            entity.setUpdatedAt(Instant.now());
            return factsRepository.save(entity);
        } catch (RuntimeException e) {
            log.debug("Could not record a facts placeholder for {}: {}", coordinates, e.toString());
            return factsRepository.findById(coordinates).orElse(null);
        }
    }

    private static Optional<PackageURL> purlOf(ComponentIdentity identity) {
        if (identity instanceof ComponentIdentity.Purl purlIdentity) {
            return Optional.of(purlIdentity.purl());
        }
        return Optional.empty();
    }

    static ComponentFacts toFacts(FirewallComponentFactsEntity entity) {
        String[] licenses = entity.getDeclaredLicenses();
        return new ComponentFacts(
                entity.getPurl(),
                entity.getState(),
                entity.getPublishedAt(),
                licenses == null ? List.of() : List.of(licenses),
                entity.getLicenseSource(),
                entity.getSource(),
                entity.getFetchedAt());
    }
}
