package com.atlasna.catalog;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The upgrade path a real database takes: V1 with existing old-style rows, then V2.
 * (Every other test only sees an empty database migrated straight to the latest version.)
 * Runs in its own schema of the shared Postgres container so it can't disturb other tests.
 */
class MigrationPathTest {

    private static final String SCHEMA = "migration_path";

    @Test
    void v2AppliesOnTopOfV1WithOldRows() {
        DataSource ds = new DriverManagerDataSource(PostgresIntegrationTest.POSTGRES.getJdbcUrl(),
                PostgresIntegrationTest.POSTGRES.getUsername(), PostgresIntegrationTest.POSTGRES.getPassword());
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");

        flyway(ds, "1").migrate();
        jdbc.update("INSERT INTO " + SCHEMA + ".users (full_name, email, password_hash, role, enabled, created_at) "
                + "VALUES ('Old User', 'old@example.com', '$2a$10$x', 'CUSTOMER', true, now())");
        jdbc.update("INSERT INTO " + SCHEMA + ".products (name, description, price, category, stock_quantity, active, created_at, updated_at) "
                + "VALUES ('Old demo row', 'x', 3100.00, 'HOME_AND_KITCHEN', 5, true, now(), now())");

        flyway(ds, "latest").migrate();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + SCHEMA + ".products", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + SCHEMA + ".categories WHERE parent_id IS NULL", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + SCHEMA + ".categories WHERE parent_id IS NOT NULL", Integer.class)).isEqualTo(15);
        // Every child has its parent's type.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + SCHEMA + ".categories c JOIN " + SCHEMA
                + ".categories p ON c.parent_id = p.id WHERE c.type <> p.type", Integer.class)).isZero();
        // Existing users survive and get a nullable phone.
        assertThat(jdbc.queryForObject("SELECT phone FROM " + SCHEMA + ".users WHERE email = 'old@example.com'", String.class)).isNull();

        // Two users without a phone are fine; two with the same phone are not (partial unique index).
        jdbc.update("INSERT INTO " + SCHEMA + ".users (full_name, email, password_hash, role, enabled, phone) "
                + "VALUES ('A', 'a@example.com', 'h', 'CUSTOMER', true, '+213551234567')");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO " + SCHEMA + ".users (full_name, email, password_hash, role, enabled, phone) "
                + "VALUES ('B', 'b@example.com', 'h', 'CUSTOMER', true, '+213551234567')"))
                .isInstanceOf(DataIntegrityViolationException.class);

        jdbc.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
    }

    private static Flyway flyway(DataSource ds, String target) {
        return Flyway.configure().dataSource(ds).schemas(SCHEMA).target(target)
                .locations("classpath:db/migration").load();
    }
}
