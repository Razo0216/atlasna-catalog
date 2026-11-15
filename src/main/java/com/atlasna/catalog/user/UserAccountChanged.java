package com.atlasna.catalog.user;

import com.atlasna.catalog.common.Actor;

/** Account events for the audit log: USER_CREATED, USER_ENABLED, USER_DISABLED, PASSWORD_CHANGED. Never a password. */
public record UserAccountChanged(long userId, String action, String role, Actor actor) {}
