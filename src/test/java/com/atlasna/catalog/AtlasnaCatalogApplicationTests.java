package com.atlasna.catalog;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** Smoke test: the whole application context starts with the test profile (H2 + Flyway). */
@SpringBootTest
@ActiveProfiles("test")
class AtlasnaCatalogApplicationTests {

	@Test
	void contextLoads() {
	}

}
