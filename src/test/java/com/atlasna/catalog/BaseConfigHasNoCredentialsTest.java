package com.atlasna.catalog;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * application.yml applies to every environment, so it must not carry usable credentials.
 * Real values come from environment variables; dev values live in application-dev.yml.
 */
class BaseConfigHasNoCredentialsTest {

    @Test
    void baseConfigContainsNoCredentialValues() throws Exception {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"));

        List<String> leaked = sources.stream()
                .map(EnumerablePropertySource.class::cast)
                .flatMap(source -> Arrays.stream(source.getPropertyNames())
                        .filter(BaseConfigHasNoCredentialsTest::isSensitive)
                        .filter(name -> !isEmptyEnvPlaceholder(String.valueOf(source.getProperty(name))))
                        .map(name -> name + "=" + source.getProperty(name)))
                .toList();

        assertThat(leaked).as("credentials hardcoded in application.yml").isEmpty();
    }

    private static boolean isSensitive(String name) {
        String n = name.toLowerCase();
        return n.contains("password") || n.contains("secret") || n.contains("username")
                || n.equals("spring.datasource.url");
    }

    /** Allowed form: ${SOME_ENV_VAR} or ${SOME_ENV_VAR:} with no fallback value. */
    private static boolean isEmptyEnvPlaceholder(String value) {
        return value.matches("\\$\\{[A-Z0-9_]+:?}");
    }
}
