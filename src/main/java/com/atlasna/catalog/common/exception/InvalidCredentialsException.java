package com.atlasna.catalog.common.exception;

/**
 * Thrown when login fails; mapped to 401 Unauthorized.
 *
 * <p>Deliberately used for both "unknown email" and "wrong password" so the response never
 * reveals which emails have accounts (prevents account enumeration).
 */
public class InvalidCredentialsException extends RuntimeException {
    public InvalidCredentialsException() {
        super("Invalid email or password");
    }
}
