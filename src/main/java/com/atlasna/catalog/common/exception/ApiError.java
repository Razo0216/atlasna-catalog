package com.atlasna.catalog.common.exception;

import java.time.Instant;
import java.util.Map;

/**
 * The single JSON shape returned for every error response, built by {@link GlobalExceptionHandler}.
 *
 * <p>Example:
 * <pre>
 * { "timestamp": "...", "status": 400, "error": "Validation Failed",
 *   "message": "One or more fields are invalid", "fieldErrors": { "email": "Email must be valid" } }
 * </pre>
 *
 * @param timestamp   when the error was produced
 * @param status      HTTP status code, repeated in the body for clients that only read JSON
 * @param error       short error title (usually the HTTP reason phrase)
 * @param message     human-readable explanation, safe to show to the client
 * @param fieldErrors per-field validation messages; {@code null} for non-validation errors
 */
public record ApiError(Instant timestamp, int status, String error, String message, Map<String, String> fieldErrors) {
    public static ApiError of(int status, String error, String message) {
        return new ApiError(Instant.now(), status, error, message, null);
    }
    public static ApiError ofFieldErrors(int status, String error, String message, Map<String, String> fieldErrors) {
        return new ApiError(Instant.now(), status, error, message, fieldErrors);
    }
}
