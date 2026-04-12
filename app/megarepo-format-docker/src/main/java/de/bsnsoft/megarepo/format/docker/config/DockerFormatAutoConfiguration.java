package de.bsnsoft.megarepo.format.docker.config;

import de.bsnsoft.megarepo.core.format.FormatPluginRegistrar;
import de.bsnsoft.megarepo.core.format.FormatRegistry;
import de.bsnsoft.megarepo.format.docker.DockerFormatPlugin;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@ComponentScan(basePackages = "de.bsnsoft.megarepo.format.docker")
public class DockerFormatAutoConfiguration {

    @Bean
    public FormatPluginRegistrar dockerPluginRegistrar(FormatRegistry registry, DockerFormatPlugin plugin) {
        return new FormatPluginRegistrar(registry, plugin);
    }
}
