package de.bsnsoft.megarepo.core.format;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central index of all {@link FormatPlugin}s discovered at boot.
 *
 * <p>Each plugin is registered under its canonical {@link FormatPlugin#getFormat()
 * format key} as well as every entry of {@link FormatPlugin#getAliases() its
 * alias set}. Aliases exist for historical reasons — most importantly, the
 * Maven plugin lives under {@code "maven2"} (Sonatype-Nexus convention) but
 * gets requests for {@code "maven"} from older configs, hand-rolled YAML
 * presets, and any pre-V9 database row that {@code FirstRunSetup} originally
 * seeded incorrectly. Indexing both keys means a request for {@code "maven"}
 * still routes to the very same {@link MavenFormatPlugin} instance, instead of
 * surfacing as an {@code UnsupportedFormatException} that takes down the HTTP
 * request.
 *
 * <p>{@link #getSupportedFormats()} continues to return only canonical keys so
 * the {@code RepositoryController}'s validation logic and any UI dropdowns
 * stay free of duplicate noise.
 */
@Component
public class FormatRegistry {

    private final Map<String, FormatPlugin> plugins = new ConcurrentHashMap<>();
    private final Set<String> canonicalFormats = ConcurrentHashMap.newKeySet();

    public void register(FormatPlugin plugin) {
        String canonical = plugin.getFormat();
        plugins.put(canonical, plugin);
        canonicalFormats.add(canonical);
        for (String alias : plugin.getAliases()) {
            if (alias == null || alias.isBlank() || alias.equals(canonical)) {
                continue;
            }
            plugins.putIfAbsent(alias, plugin);
        }
    }

    public FormatPlugin getPlugin(String format) {
        FormatPlugin plugin = plugins.get(format);
        if (plugin == null) {
            throw new UnsupportedFormatException(format);
        }
        return plugin;
    }

    public Collection<FormatPlugin> getAllPlugins() {
        return Collections.unmodifiableCollection(new LinkedHashSet<>(plugins.values()));
    }

    /**
     * Returns only the canonical format keys — aliases are intentionally
     * excluded so that creation-time validation and UI dropdowns work with
     * a clean, deduplicated set.
     */
    public Set<String> getSupportedFormats() {
        return Collections.unmodifiableSet(new HashSet<>(canonicalFormats));
    }

    /**
     * Returns every key under which a plugin is resolvable, including
     * aliases. Useful for diagnostic endpoints; do not use for validation.
     */
    public Set<String> getResolvableFormatKeys() {
        return Collections.unmodifiableSet(new HashSet<>(plugins.keySet()));
    }
}
