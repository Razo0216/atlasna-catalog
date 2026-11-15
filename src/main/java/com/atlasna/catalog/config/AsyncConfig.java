package com.atlasna.catalog.config;

import com.mongodb.MongoClientSettings;
import org.springframework.boot.mongodb.autoconfigure.MongoClientSettingsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.concurrent.TimeUnit;

/**
 * {@code @Async} listeners (the audit log) run on Spring Boot's task executor. MongoDB only holds the audit log, so it
 * must fail fast instead of waiting the driver's default 30 s for a server: 2 s to find it, 5 s per operation.
 */
@Configuration
@EnableAsync
class AsyncConfig {

    @Bean
    MongoClientSettingsBuilderCustomizer fastFailingMongo() {
        return (MongoClientSettings.Builder settings) -> settings
                .applyToClusterSettings(c -> c.serverSelectionTimeout(2, TimeUnit.SECONDS))
                .applyToSocketSettings(s -> s.connectTimeout(2, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS));
    }
}
