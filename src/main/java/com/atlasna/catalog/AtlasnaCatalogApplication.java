package com.atlasna.catalog;

import com.atlasna.catalog.config.AdminBootstrapProperties;
import com.atlasna.catalog.security.JwtProperties;
import com.atlasna.catalog.security.LoginRateLimitProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({JwtProperties.class, AdminBootstrapProperties.class, LoginRateLimitProperties.class})
public class AtlasnaCatalogApplication {
    public static void main(String[] args) {
        SpringApplication.run(AtlasnaCatalogApplication.class, args);
    }
}
