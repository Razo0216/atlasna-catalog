package com.atlasna.catalog.common.exception;

public class EmailAlreadyInUseException extends RuntimeException {
    public EmailAlreadyInUseException(String email) {
        super("An account with email '" + email + "' already exists");
    }
}
