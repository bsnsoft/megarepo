package de.bsnsoft.megarepo.app.config;

import de.bsnsoft.megarepo.repository.proxy.OutboundProxyProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({MegaRepoProperties.class, OutboundProxyProperties.class})
public class MegaRepoConfig {
}
