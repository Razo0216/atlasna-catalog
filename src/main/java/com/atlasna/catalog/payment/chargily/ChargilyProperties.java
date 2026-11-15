package com.atlasna.catalog.payment.chargily;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * {@code atlasna.payment.chargily.*}. The secret key comes from the environment ({@code CHARGILY_SECRET_KEY}) or a
 * secret manager — never from git. Without a key, card payment is simply not offered.
 *
 * @param baseUrl        test mode {@code https://pay.chargily.net/test/api/v2} (live: {@code …/api/v2})
 * @param connectTimeout how long to wait for a TCP connection
 * @param readTimeout    how long to wait for an answer
 */
@ConfigurationProperties(prefix = "atlasna.payment.chargily")
public record ChargilyProperties(String baseUrl, String secretKey, Duration connectTimeout, Duration readTimeout) {
    public ChargilyProperties {
        if (baseUrl == null || baseUrl.isBlank()) baseUrl = "https://pay.chargily.net/test/api/v2";
        if (connectTimeout == null) connectTimeout = Duration.ofSeconds(2);
        if (readTimeout == null) readTimeout = Duration.ofSeconds(5);
    }

    boolean configured() {
        return secretKey != null && !secretKey.isBlank();
    }
}
