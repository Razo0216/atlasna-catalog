package com.atlasna.catalog.common.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

import java.util.List;
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
    /** Machine-readable specifics (e.g. which products lack stock); null otherwise. */
    private final List<Map<String, Object>> details;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    public ApiException(HttpStatus status, String code, String message, Map<String, String> fieldErrors) {
        this(status, code, message, fieldErrors, null);
    }

    private ApiException(HttpStatus status, String code, String message, Map<String, String> fieldErrors,
                         List<Map<String, Object>> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.fieldErrors = fieldErrors;
        this.details = details;
    }

    /** Same error with machine-readable details attached (rendered as {@code details} in the JSON). */
    public ApiException withDetails(List<Map<String, Object>> details) {
        return new ApiException(status, code, getMessage(), fieldErrors, details);
    }

    public static ApiException forbidden(String code, String message) {
        return new ApiException(HttpStatus.FORBIDDEN, code, message);
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
