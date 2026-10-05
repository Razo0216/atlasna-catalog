package com.atlasna.catalog.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Vectors live in phone-number-vectors.csv (also copied to docs/snippets/) so the frontend's
 * phone.ts is tested against exactly the same rows and the two can't drift apart.
 */
class PhoneNumbersTest {

    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @CsvFileSource(resources = "/phone-number-vectors.csv", numLinesToSkip = 1)
    void normalizesSharedVectors(String raw, String expected) {
        assertThat(PhoneNumbers.normalize(raw).orElse(null)).isEqualTo(expected);
        assertThat(PhoneNumbers.isValid(raw)).isEqualTo(expected != null);
    }

    @Test
    void nullIsInvalid() {
        assertThat(PhoneNumbers.normalize(null)).isEmpty();
        assertThat(PhoneNumbers.isValid(null)).isFalse();
    }
}
