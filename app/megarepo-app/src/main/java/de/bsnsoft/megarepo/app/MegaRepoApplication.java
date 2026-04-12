package de.bsnsoft.megarepo.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "de.bsnsoft.megarepo")
public class MegaRepoApplication {

    public static void main(String[] args) {
        SpringApplication.run(MegaRepoApplication.class, args);
    }
}
