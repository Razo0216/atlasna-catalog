package com.atlasna.catalog.common;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Bean Validation constraint: the value must be an Algerian mobile number (05/06/07) in any common spelling
 * ({@code 0551 23 45 67}, {@code +213551234567}, {@code 00213-551-234-567}). Delegates to {@link PhoneNumbers}.
 * {@code null} counts as valid, so combine with {@code @NotBlank} when the field is required.
 */
@Documented
@Constraint(validatedBy = AlgerianMobile.Validator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface AlgerianMobile {

    String message() default "Phone must be an Algerian mobile number (05, 06 or 07)";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<AlgerianMobile, String> {
        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return value == null || value.isBlank() || PhoneNumbers.isValid(value);
        }
    }
}
