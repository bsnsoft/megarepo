package de.bsnsoft.megarepo.format.nuget.config;

import de.bsnsoft.megarepo.core.format.FormatPluginRegistrar;
import de.bsnsoft.megarepo.core.format.FormatRegistry;
import de.bsnsoft.megarepo.format.nuget.NugetFormatPlugin;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@ComponentScan(basePackages = "de.bsnsoft.megarepo.format.nuget")
public class NugetFormatAutoConfiguration {

    @Bean
    public FormatPluginRegistrar nugetPluginRegistrar(FormatRegistry registry, NugetFormatPlugin plugin) {
        return new FormatPluginRegistrar(registry, plugin);
    }
}
