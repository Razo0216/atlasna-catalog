package com.atlasna.catalog;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base class for tests that need the full application on a real database.
 *
 * <p>Uses the same engine as production (PostgreSQL 16) instead of H2: the schema relies on
 * Postgres-only features (JSONB, partial unique indexes, NUMERIC semantics) that H2 can't reproduce.
 *
 * <p>One container is started for the whole test run (static, started once, never stopped explicitly;
 * Testcontainers' Ryuk sidecar removes it when the JVM exits) and shared by every subclass, so the
 * cost is paid once. {@code @ServiceConnection} points Spring's datasource at it; Flyway then migrates it.
 * Requires Docker.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class PostgresIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static {
        POSTGRES.start();
    }
}
