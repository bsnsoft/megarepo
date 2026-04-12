package de.bsnsoft.megarepo.core.format;

import org.springframework.beans.factory.InitializingBean;

public class FormatPluginRegistrar implements InitializingBean {

    private final FormatRegistry registry;
    private final FormatPlugin plugin;

    public FormatPluginRegistrar(FormatRegistry registry, FormatPlugin plugin) {
        this.registry = registry;
        this.plugin = plugin;
    }

    @Override
    public void afterPropertiesSet() {
        registry.register(plugin);
    }
}
