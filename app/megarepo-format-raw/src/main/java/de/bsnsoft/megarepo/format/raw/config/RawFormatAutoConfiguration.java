package de.bsnsoft.megarepo.format.raw.config;

import de.bsnsoft.megarepo.core.format.FormatPluginRegistrar;
import de.bsnsoft.megarepo.core.format.FormatRegistry;
import de.bsnsoft.megarepo.format.raw.RawFormatPlugin;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@ComponentScan(basePackages = "de.bsnsoft.megarepo.format.raw")
public class RawFormatAutoConfiguration {

    @Bean
    public FormatPluginRegistrar rawPluginRegistrar(FormatRegistry registry, RawFormatPlugin plugin) {
        return new FormatPluginRegistrar(registry, plugin);
    }
}
