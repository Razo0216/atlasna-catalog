package com.atlasna.catalog.common.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * A business error with an HTTP status and a stable machine-readable {@code code}
 * (e.g. PRODUCT_NOT_FOUND, PRODUCT_TOO_HEAVY; full list in docs/02-DOMAIN-AND-DATA-MODEL.md §7).
 * {@code message} is English for developers; clients translate {@code code}. Rendered by GlobalExceptionHandler.
 */
@Getter
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    /** Per-field messages for 400 validation errors; null otherwise. */
    private final Map<String, String> fieldErrors;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    public ApiException(HttpStatus status, String code, String message, Map<String, String> fieldErrors) {
        super(message);
        this.status = status;
        this.code = code;
        this.fieldErrors = fieldErrors;
    }

    public static ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    /** 422: the request is well-formed but breaks a business rule (e.g. an item that is too heavy). */
    public static ApiException businessRule(String code, String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, code, message);
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    /** 400 VALIDATION_FAILED for one field, same shape as a Bean Validation failure. */
    public static ApiException invalidField(String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "One or more fields are invalid",
                Map.of(field, message));
    }
}
