package de.bsnsoft.megarepo.app.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(MegaRepoProperties.class)
public class MegaRepoConfig {
}
