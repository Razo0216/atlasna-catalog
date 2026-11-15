package com.atlasna.catalog;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 5 preflight: the app really talks to the Redis and MongoDB containers, and WireMock (the fake payment
 * gateway of the Phase 5 resilience tests) runs on this classpath. If this fails, nothing else in Phase 5 can work.
 */
class InfrastructureSmokeTest extends PostgresIntegrationTest {

    @Autowired StringRedisTemplate redis;
    @Autowired MongoTemplate mongo;

    @Test
    void redisStoresAndExpiresKeys() {
        redis.opsForValue().set("atlasna:test:smoke", "ok", Duration.ofSeconds(30));
        assertThat(redis.opsForValue().get("atlasna:test:smoke")).isEqualTo("ok");
        assertThat(redis.getExpire("atlasna:test:smoke")).isBetween(1L, 30L);
        redis.delete("atlasna:test:smoke");
    }

    @Test
    void mongoStoresDocuments() {
        mongo.insert(new Document("kind", "smoke").append("ok", true), "smoke_test");
        assertThat(mongo.getCollection("smoke_test").countDocuments(new Document("kind", "smoke"))).isEqualTo(1);
        mongo.dropCollection("smoke_test");
    }

    @Test
    void wireMockServesStubs() throws Exception {
        WireMockServer server = new WireMockServer(options().dynamicPort());
        server.start();
        try {
            server.stubFor(get("/ping").willReturn(okJson("{\"ok\":true}")));
            HttpResponse<String> res = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(server.baseUrl() + "/ping")).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(res.statusCode()).isEqualTo(200);
            assertThat(res.body()).contains("\"ok\":true");
        } finally {
            server.stop();
        }
    }
}
