package com.atlasna.catalog;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base class for tests that need the full application on real infrastructure.
 *
 * <p>Uses the same engines as production instead of in-memory fakes: PostgreSQL 16 (the schema relies on
 * Postgres-only features — JSONB, partial unique indexes, NUMERIC semantics — that H2 can't reproduce),
 * Redis 7 (cache, rate limits, refresh tokens) and MongoDB 8 (audit log).
 *
 * <p>Containers are started once for the whole test run (static, never stopped explicitly; Testcontainers'
 * Ryuk sidecar removes them when the JVM exits) and shared by every subclass, so the cost is paid once.
 * {@code @ServiceConnection} points Spring's datasource / Redis / MongoDB clients at them; Flyway then migrates
 * Postgres. Requires Docker.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class PostgresIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    /** Same image as docker-compose. "redis" tells Spring which connection details this generic container provides. */
    @ServiceConnection(name = "redis")
    @SuppressWarnings("resource")
    protected static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    @ServiceConnection
    protected static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8.0");

    static {
        POSTGRES.start();
        REDIS.start();
        MONGO.start();
    }
}
