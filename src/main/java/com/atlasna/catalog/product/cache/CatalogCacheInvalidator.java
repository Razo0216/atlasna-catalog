package com.atlasna.catalog.product.cache;

import com.atlasna.catalog.inventory.StockChanged;
import com.atlasna.catalog.product.CatalogChanged;
import com.atlasna.catalog.store.StoreSettingsChanged;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Invalidates the public catalog cache AFTER the change is committed (never before: a reader could otherwise
 * re-cache the old row in between), once per transaction however many products it touched.
 *
 * <p>Deliberately a plain {@code @EventListener} that registers an after-commit callback, not a
 * {@code @TransactionalEventListener}: Spring Modulith records every transactional listener in its event-publication
 * table and completes the record with a second database connection while the first is still held. Under a burst of
 * concurrent orders that exhausted the connection pool (caught by OrderConcurrencyTests). A cache bump is a single
 * Redis INCR that needs no durability guarantee — the TTL is the safety net — so no publication record is needed.
 */
@Component
@RequiredArgsConstructor
class CatalogCacheInvalidator {

    private static final Object REGISTERED = new Object();

    private final CatalogCache cache;

    @EventListener
    void on(CatalogChanged event) {
        invalidateAfterCommit();
    }

    @EventListener
    void on(StockChanged event) {
        invalidateAfterCommit();
    }

    @EventListener
    void on(StoreSettingsChanged event) {
        invalidateAfterCommit();
    }

    /** A restarted app may run on a reset database (seed data): never trust what Redis kept from before. */
    @EventListener(ApplicationReadyEvent.class)
    void onStartup() {
        cache.invalidateAll();
    }

    private void invalidateAfterCommit() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            cache.invalidateAll(); // not in a transaction: the change is already visible
            return;
        }
        if (TransactionSynchronizationManager.hasResource(REGISTERED)) return; // one bump per transaction
        TransactionSynchronizationManager.bindResource(REGISTERED, Boolean.TRUE);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                cache.invalidateAll();
            }

            @Override
            public void afterCompletion(int status) {
                TransactionSynchronizationManager.unbindResourceIfPossible(REGISTERED);
            }
        });
    }
}
