package de.bsnsoft.megarepo.storage.config;

import de.bsnsoft.megarepo.storage.BlobStoreManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class StorageAutoConfiguration {

    @Bean
    public BlobStoreManager blobStoreManager() {
        return new BlobStoreManager();
    }
}
