package de.bsnsoft.megarepo.database.config;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@Configuration
@EnableJpaRepositories(basePackages = "de.bsnsoft.megarepo.database.repository")
@EntityScan(basePackages = "de.bsnsoft.megarepo.database.entity")
public class JpaConfig {
}
