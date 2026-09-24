package com.atlasna.catalog.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that startup fails fast without a usable JWT secret. {@code ApplicationContextRunner} starts
 * only the properties binding, so each case runs in milliseconds.
 */
class JwtPropertiesValidationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Config.class)
            .withPropertyValues("atlasna.jwt.expiration-minutes=60");

    @Test
    void startupFailsWhenSecretIsMissing() {
        runner.run(context -> assertThat(context).hasFailed()
                .getFailure().rootCause().hasMessageContaining("atlasna.jwt.secret"));
    }

    @Test
    void startupFailsWhenSecretIsTooShortForHs256() {
        runner.withPropertyValues("atlasna.jwt.secret=only-31-characters-long-secret!")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("at least 32 characters"));
    }

    @Test
    void startupSucceedsWithStrongSecret() {
        runner.withPropertyValues("atlasna.jwt.secret=a-perfectly-long-random-secret-of-sufficient-length")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(JwtProperties.class));
    }

    @Configuration
    @EnableConfigurationProperties(JwtProperties.class)
    static class Config {
    }
}
