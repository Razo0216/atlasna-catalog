# ADR 0002 — Separate public and admin product DTOs

**Status:** accepted (Phase 1) · **Date:** 2026-11-10

## Context
Exact stock levels, the `active` flag, the optimistic-lock `version` and low-stock thresholds are internal. Exposing
them on the public storefront leaks business data (competitors can read stock) and couples the frontend to admin fields.

## Decision
Two response types: `ProductResponse` (public — shows `availability` `IN_STOCK | LOW_STOCK | OUT_OF_STOCK` and
`maxOrderQuantity`, never the raw stock) and `AdminProductResponse` (adds `stockQuantity`, `lowStockThreshold`,
`active`, `version`, timestamps). Public routes live under `/api/products`, admin routes under `/api/admin/products`.
Entities are never serialized directly.

## Consequences
- A test asserts the public JSON has no `stockQuantity`, `active` or `version`.
- Adding a field means choosing deliberately which DTO gets it.
- Slight duplication in `ProductMapper`, accepted for the safety.
