package com.atlasna.catalog.user;

/**
 * Single-store model: ADMIN manages the catalog, CUSTOMER shops.
 * A future SELLER role slots in here without touching anything else.
 */
public enum Role {
    ADMIN,
    CUSTOMER
}
