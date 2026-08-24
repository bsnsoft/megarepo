package de.bsnsoft.megarepo.repository.advisory;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Default {@link AdvisoryAliasResolver}: two advisories are the same
 * vulnerability exactly when they carry the same upstream id.
 *
 * <p>The comparison is case-insensitive and whitespace-trimmed. That is not
 * cosmetic — {@code advisory.id} is a case-sensitive primary key, so a feed that
 * publishes {@code cve-2021-44228} where another publishes
 * {@code CVE-2021-44228} produces two rows that this resolver still merges into
 * one finding.
 *
 * <p>The canonical id returned is one of the input ids (the first seen for a
 * given normalised key), never the normalised form itself, so callers can look
 * the advisory row back up by it.
 *
 * <p>This deliberately resolves nothing beyond that. See
 * {@link AdvisoryAliasResolver} for why cross-id aliases (GHSA ↔ CVE) cannot be
 * resolved under the Phase 1 ingest contract, and how they will plug in.
 */
@Component
public class IdentityAliasResolver implements AdvisoryAliasResolver {

    @Override
    public Map<String, String> canonicalIds(Collection<String> advisoryIds) {
        Map<String, String> canonicalByNormalised = new HashMap<>();
        Map<String, String> result = new HashMap<>();
        for (String id : advisoryIds) {
            if (id == null) {
                continue;
            }
            String normalised = id.trim().toUpperCase(Locale.ROOT);
            result.put(id, canonicalByNormalised.computeIfAbsent(normalised, key -> id));
        }
        return result;
    }
}
