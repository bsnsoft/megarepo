package de.bsnsoft.megarepo.database.repository;

import de.bsnsoft.megarepo.database.entity.FirewallEnforcementSettingsEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Access to the singleton global enforcement switch.
 *
 * <p>Both the administration surface that writes the switch and the enforcement
 * path that reads it go through here — see
 * {@link FirewallEnforcementSettingsEntity} for why there is exactly one
 * representation of this flag.
 */
@Repository
public interface FirewallEnforcementSettingsJpaRepository
        extends JpaRepository<FirewallEnforcementSettingsEntity, Integer> {

    /**
     * The switch as it currently stands.
     *
     * <p>V17 seeds the row, so it exists on every migrated database. The
     * fallback covers only the case of someone having deleted it by hand, and
     * answers with a disabled, unsaved instance: a missing switch must read as
     * "not enforcing", never as "enforcing", and must not be silently recreated
     * by a read.
     */
    default FirewallEnforcementSettingsEntity current() {
        return findById(FirewallEnforcementSettingsEntity.SINGLETON_ID)
                .orElseGet(FirewallEnforcementSettingsEntity::new);
    }
}
