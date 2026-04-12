package de.bsnsoft.megarepo.core.format;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class FormatRegistry {

    private final Map<String, FormatPlugin> plugins = new ConcurrentHashMap<>();

    public void register(FormatPlugin plugin) {
        plugins.put(plugin.getFormat(), plugin);
    }

    public FormatPlugin getPlugin(String format) {
        FormatPlugin plugin = plugins.get(format);
        if (plugin == null) {
            throw new UnsupportedFormatException(format);
        }
        return plugin;
    }

    public Collection<FormatPlugin> getAllPlugins() {
        return Collections.unmodifiableCollection(plugins.values());
    }

    public Set<String> getSupportedFormats() {
        return Collections.unmodifiableSet(plugins.keySet());
    }
}
