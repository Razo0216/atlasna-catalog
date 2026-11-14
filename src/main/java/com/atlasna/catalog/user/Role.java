package com.atlasna.catalog.user;

/**
 * Who a user is: ADMIN runs the shop, CUSTOMER shops, STAFF prepares orders, DRIVER delivers them.
 * Single store: no seller/vendor role, ever (see the master plan).
 */
public enum Role {
    ADMIN,
    CUSTOMER,
    /** Prepares orders: confirms, packs and weighs; may restock. (Phase 3) */
    STAFF,
    /** Delivers assigned orders and records the cash collected. (Phase 3) */
    DRIVER
}
