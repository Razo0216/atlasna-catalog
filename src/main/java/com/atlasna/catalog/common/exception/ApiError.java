package com.atlasna.catalog.common.exception;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The single JSON shape returned for every error response, built by {@link GlobalExceptionHandler}.
 *
 * <p>Example:
 * <pre>
 * { "timestamp": "...", "status": 400, "error": "Validation Failed", "code": "VALIDATION_FAILED",
 *   "message": "One or more fields are invalid", "fieldErrors": { "email": "Email must be valid" } }
 * </pre>
 *
 * @param timestamp   when the error was produced
 * @param status      HTTP status code, repeated in the body for clients that only read JSON
 * @param error       short error title (usually the HTTP reason phrase)
 * @param code        stable machine-readable code (docs/02-DOMAIN-AND-DATA-MODEL.md §7). Clients translate
 *                    this, not {@code message}. Codes are never renamed.
 * @param message     English explanation for developers, safe to show
 * @param fieldErrors per-field validation messages; {@code null} for non-validation errors
 * @param details     machine-readable specifics for some codes (e.g. INSUFFICIENT_STOCK: {@code [{productId, available}]});
 *                    {@code null} otherwise
 */
public record ApiError(Instant timestamp, int status, String error, String code, String message,
                       Map<String, String> fieldErrors, List<Map<String, Object>> details) {

    public static ApiError of(int status, String error, String code, String message) {
        return new ApiError(Instant.now(), status, error, code, message, null, null);
    }

    public static ApiError of(int status, String error, String code, String message, List<Map<String, Object>> details) {
        return new ApiError(Instant.now(), status, error, code, message, null, details);
    }

    public static ApiError ofFieldErrors(int status, String code, String message, Map<String, String> fieldErrors) {
        return new ApiError(Instant.now(), status, "Validation Failed", code, message, fieldErrors, null);
    }
}
