package de.bsnsoft.megarepo.format.pypi.config;

import de.bsnsoft.megarepo.core.format.FormatPluginRegistrar;
import de.bsnsoft.megarepo.core.format.FormatRegistry;
import de.bsnsoft.megarepo.format.pypi.PypiFormatPlugin;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@ComponentScan(basePackages = "de.bsnsoft.megarepo.format.pypi")
public class PypiFormatAutoConfiguration {

    @Bean
    public FormatPluginRegistrar pypiPluginRegistrar(FormatRegistry registry, PypiFormatPlugin plugin) {
        return new FormatPluginRegistrar(registry, plugin);
    }
}
