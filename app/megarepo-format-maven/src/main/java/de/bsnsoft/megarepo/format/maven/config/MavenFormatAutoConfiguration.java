package de.bsnsoft.megarepo.format.maven.config;

import de.bsnsoft.megarepo.core.format.FormatPluginRegistrar;
import de.bsnsoft.megarepo.core.format.FormatRegistry;
import de.bsnsoft.megarepo.format.maven.MavenFormatPlugin;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@ComponentScan(basePackages = "de.bsnsoft.megarepo.format.maven")
public class MavenFormatAutoConfiguration {

    @Bean
    public FormatPluginRegistrar mavenPluginRegistrar(FormatRegistry registry, MavenFormatPlugin plugin) {
        return new FormatPluginRegistrar(registry, plugin);
    }
}
