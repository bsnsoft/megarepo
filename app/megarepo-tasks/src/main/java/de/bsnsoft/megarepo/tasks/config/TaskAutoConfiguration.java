package de.bsnsoft.megarepo.tasks.config;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@ComponentScan(basePackages = "de.bsnsoft.megarepo.tasks")
@EnableScheduling
public class TaskAutoConfiguration {}
