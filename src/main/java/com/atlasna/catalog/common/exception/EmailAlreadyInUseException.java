package com.atlasna.catalog.common.exception;

/** Thrown when registering an email that already has an account; mapped to 409 Conflict. */
public class EmailAlreadyInUseException extends RuntimeException {
    public EmailAlreadyInUseException(String email) {
        super("An account with email '" + email + "' already exists");
    }
}
