package com.atlasna.catalog.inventory;

/** Why stock changed — one row in the {@code stock_movements} ledger. */
public enum StockReason {
    /** First entry for a product: its starting stock. */
    OPENING,
    /** Sold (negative), including a weighed line that came out heavier than ordered. */
    SALE,
    /** Given back: order cancelled, line removed, or weighed lighter than ordered. */
    CANCEL_RESTORE,
    /** New goods received. */
    RESTOCK,
    /** Manual correction after a count. */
    ADJUSTMENT,
    /** Thrown away past its date. */
    EXPIRED
}
