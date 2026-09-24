package com.atlasna.catalog.common.exception;

/** Thrown when a requested entity doesn't exist (or is soft-deleted); mapped to 404 Not Found. */
public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String message) {
        super(message);
    }
}
