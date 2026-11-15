package com.atlasna.catalog.common;

/** Implemented by the authenticated principal, so shared code can read the caller's id without depending on the user module. */
public interface HasUserId {
    Long getId();
}
