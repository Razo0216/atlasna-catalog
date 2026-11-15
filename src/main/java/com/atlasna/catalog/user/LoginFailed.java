package com.atlasna.catalog.user;

import com.atlasna.catalog.common.Actor;

/**
 * A failed login, for the audit log: the email that was tried (normalised) and why — never the password.
 *
 * @param reason INVALID_CREDENTIALS, ACCOUNT_DISABLED or RATE_LIMITED
 */
public record LoginFailed(String email, String reason, Actor actor) {}
