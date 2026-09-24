package com.atlasna.catalog;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The schema is owned by Flyway; Hibernate only validates it (ddl-auto: validate).
 * If a migration and an entity disagree, this context fails to start.
 */
@SpringBootTest
@ActiveProfiles("test")
class SchemaMigrationTest {

    @Autowired Flyway flyway;

    @Test
    void allMigrationsAppliedAndNonePending() {
        var info = flyway.info();
        assertThat(info.applied()).isNotEmpty();
        assertThat(info.pending()).isEmpty();
        assertThat(info.current().getVersion().getVersion()).isEqualTo("1");
    }
}
