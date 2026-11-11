package com.atlasna.catalog.common.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Converts exceptions thrown anywhere in request handling into an HTTP status plus an {@link ApiError} body,
 * so every error response has the same JSON shape, including a stable {@code code} the frontend translates.
 *
 * <p>Spring picks the handler whose exception type is the closest match, so specific handlers win over the
 * {@code Exception} catch-all at the bottom. Messages sent to the client are always safe to show:
 * internal details (stack traces, SQL errors) are logged, never returned.
 *
 * <p>Status summary: 400 invalid input · 401 not authenticated / bad credentials / disabled · 403 wrong role ·
 * 404 not found · 409 duplicates and concurrent edits · 422 business rules · 429 login rate limit · 500 bug.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * Unique indexes (from the Flyway migrations) mapped to the code reported when a write races past the
     * service's "already taken?" check. Matched against the database error message, lower-cased.
     */
    private static final Map<String, String> UNIQUE_CONSTRAINT_CODES = new LinkedHashMap<>();
    static {
        UNIQUE_CONSTRAINT_CODES.put("uk_users_email", "EMAIL_ALREADY_IN_USE");
        UNIQUE_CONSTRAINT_CODES.put("ux_users_phone", "PHONE_ALREADY_IN_USE");
        UNIQUE_CONSTRAINT_CODES.put("ux_categories_slug", "SLUG_CONFLICT");
        UNIQUE_CONSTRAINT_CODES.put("ux_products_slug", "SLUG_CONFLICT");
        UNIQUE_CONSTRAINT_CODES.put("ux_products_sku", "SKU_CONFLICT");
        UNIQUE_CONSTRAINT_CODES.put("ux_products_barcode", "BARCODE_CONFLICT");
    }

    /** 400: a {@code @Valid} request body broke its constraints; reports every invalid field. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(fe -> fieldErrors.putIfAbsent(fe.getField(), fe.getDefaultMessage()));
        return ResponseEntity.badRequest().body(ApiError.ofFieldErrors(
                HttpStatus.BAD_REQUEST.value(), "VALIDATION_FAILED", "One or more fields are invalid", fieldErrors));
    }

    /** 409: registration with an email that already has an account. */
    @ExceptionHandler(EmailAlreadyInUseException.class)
    public ResponseEntity<ApiError> handleEmailInUse(EmailAlreadyInUseException ex) {
        return error(HttpStatus.CONFLICT, "Email Already In Use", "EMAIL_ALREADY_IN_USE", ex.getMessage());
    }

    /**
     * 401: login failed. The message is fixed and identical for unknown emails and wrong passwords,
     * so it can't be used to discover which accounts exist.
     */
    @ExceptionHandler({InvalidCredentialsException.class, BadCredentialsException.class})
    public ResponseEntity<ApiError> handleBadCredentials(RuntimeException ex) {
        return error(HttpStatus.UNAUTHORIZED, "Unauthorized", "INVALID_CREDENTIALS", "Invalid email or password");
    }

    /**
     * 401: correct password, but the account is disabled. SecurityConfig runs the account-status check only
     * after the password matched, so this never tells a guesser that an account exists.
     */
    @ExceptionHandler(DisabledException.class)
    public ResponseEntity<ApiError> handleDisabled(DisabledException ex) {
        return error(HttpStatus.UNAUTHORIZED, "Unauthorized", "ACCOUNT_DISABLED", "This account is disabled");
    }

    /** Reached via SecurityConfig's authentication entry point when a protected endpoint has no valid token. */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiError> handleUnauthenticated(AuthenticationException ex) {
        return error(HttpStatus.UNAUTHORIZED, "Unauthorized", "UNAUTHENTICATED",
                "Authentication is required to access this resource");
    }

    /** 429: login attempts are rate-limited; {@code Retry-After} tells the client how long to wait. */
    @ExceptionHandler(TooManyRequestsException.class)
    public ResponseEntity<ApiError> handleTooManyRequests(TooManyRequestsException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()))
                .body(ApiError.of(HttpStatus.TOO_MANY_REQUESTS.value(), "Too Many Requests", "TOO_MANY_REQUESTS",
                        ex.getMessage()));
    }

    /** Business errors thrown by services: status, code, message and (for 400) field errors come from the exception. */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApiException(ApiException ex) {
        HttpStatus status = ex.getStatus();
        ApiError body = ex.getFieldErrors() == null
                ? ApiError.of(status.value(), status.getReasonPhrase(), ex.getCode(), ex.getMessage())
                : ApiError.ofFieldErrors(status.value(), ex.getCode(), ex.getMessage(), ex.getFieldErrors());
        return ResponseEntity.status(status).body(body);
    }

    /**
     * 409: two admins edited the same product at once and this write lost the race (the row's version changed
     * between read and write). The client should reload and retry.
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> handleOptimisticLock(ObjectOptimisticLockingFailureException ex) {
        return error(HttpStatus.CONFLICT, "Conflict", "CONCURRENT_MODIFICATION",
                "This record was changed by someone else. Reload it and try again.");
    }

    /**
     * 409 when a unique index rejected a write (two requests raced past the service's own check);
     * any other integrity failure is a bug and goes to the 500 path.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataIntegrity(DataIntegrityViolationException ex) {
        String detail = String.valueOf(ex.getMostSpecificCause().getMessage()).toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> e : UNIQUE_CONSTRAINT_CODES.entrySet()) {
            if (detail.contains(e.getKey())) {
                return error(HttpStatus.CONFLICT, "Conflict", e.getValue(), "This value is already in use");
            }
        }
        return internalError(ex);
    }

    /** 404: the requested entity doesn't exist or has been soft-deleted. */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(ResourceNotFoundException ex) {
        return error(HttpStatus.NOT_FOUND, "Not Found", "NOT_FOUND", ex.getMessage());
    }

    /**
     * 403: the caller is authenticated but lacks the required role, e.g. a CUSTOMER calling an
     * {@code @PreAuthorize("hasRole('ADMIN')")} endpoint (Spring throws AuthorizationDeniedException,
     * a subclass of AccessDeniedException).
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex) {
        return error(HttpStatus.FORBIDDEN, "Forbidden", "FORBIDDEN", "You don't have permission to do that");
    }

    /** 400: the body isn't valid JSON, or a value can't be converted (e.g. an unknown enum constant). */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableBody(HttpMessageNotReadableException ex) {
        return error(HttpStatus.BAD_REQUEST, "Bad Request", "VALIDATION_FAILED", "Request body is missing or malformed");
    }

    /** 400: a query or path parameter has the wrong type, e.g. {@code ?type=FOO} or {@code /products/abc}. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return error(HttpStatus.BAD_REQUEST, "Bad Request", "VALIDATION_FAILED",
                "Invalid value '" + ex.getValue() + "' for parameter '" + ex.getName() + "'");
    }

    /**
     * Catch-all. Framework errors keep their real status (404 no route, 405 wrong method, 415 media type, ...)
     * with the status name as code; anything else is an unexpected bug: log the full stack trace and return a
     * generic 500 that reveals nothing internal.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
        if (ex instanceof ErrorResponse errorResponse) {
            HttpStatus status = HttpStatus.valueOf(errorResponse.getStatusCode().value());
            return error(status, status.getReasonPhrase(), status.name(), status.getReasonPhrase());
        }
        return internalError(ex);
    }

    private ResponseEntity<ApiError> internalError(Exception ex) {
        log.error("Unhandled exception", ex);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", "INTERNAL_ERROR",
                "Something went wrong. Please try again.");
    }

    private static ResponseEntity<ApiError> error(HttpStatus status, String title, String code, String message) {
        return ResponseEntity.status(status).body(ApiError.of(status.value(), title, code, message));
    }
}
