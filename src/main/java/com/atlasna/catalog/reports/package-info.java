/**
 * Dashboard and reports for the owner (Phase 6). Read-only SQL over the order, payment, catalog and delivery tables:
 * no entity of another module is loaded or changed here, so this module depends on nothing but the database
 * schema (and the inventory module's public reports for low stock and expiring lots).
 */
package com.atlasna.catalog.reports;
