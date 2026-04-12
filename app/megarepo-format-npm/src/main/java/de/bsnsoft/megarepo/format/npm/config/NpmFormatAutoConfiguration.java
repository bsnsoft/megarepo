package de.bsnsoft.megarepo.format.npm.config;

import de.bsnsoft.megarepo.core.format.FormatPluginRegistrar;
import de.bsnsoft.megarepo.core.format.FormatRegistry;
import de.bsnsoft.megarepo.format.npm.NpmFormatPlugin;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@ComponentScan(basePackages = "de.bsnsoft.megarepo.format.npm")
public class NpmFormatAutoConfiguration {

    @Bean
    public FormatPluginRegistrar npmPluginRegistrar(FormatRegistry registry, NpmFormatPlugin plugin) {
        return new FormatPluginRegistrar(registry, plugin);
    }
}
