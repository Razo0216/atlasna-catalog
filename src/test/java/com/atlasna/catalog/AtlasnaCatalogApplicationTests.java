package com.atlasna.catalog;

import org.junit.jupiter.api.Test;

/** Smoke test: the whole application context starts with the test profile (Postgres via Testcontainers + Flyway). */
class AtlasnaCatalogApplicationTests extends PostgresIntegrationTest {

	@Test
	void contextLoads() {
	}

}
