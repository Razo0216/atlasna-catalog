# ADR 0001 — Categories are a database table, not a Java enum

**Status:** accepted (Phase 1) · **Date:** 2026-11-10

## Context
V1 stored the category as a `VARCHAR` with a `CHECK` constraint mirroring a Java enum (`ELECTRONICS`, `FASHION`, …).
Atlasna sells groceries and beauty products in two departments with ~15 sub-categories, each with a French and an
Arabic name, a URL slug and a display order. The owner will want to add, rename and reorder them without a deployment.

## Decision
`categories` is a table (V2): `id`, `slug` (unique), `parent_id` (max depth 2), `type` (`GROCERY` | `BEAUTY`),
`name_fr`, `name_ar`, `sort_order`, `active`. Products reference a **leaf** category by id. The seed list lives in the
migration as reference data. Only the two department *types* stay an enum (`CategoryType`), because code branches on them
(e.g. SKU prefix `GRC-` / `BTY-`).

## Consequences
- Admins manage categories through `/api/admin/categories`; no migration needed for a new sub-category.
- The public API filters by **slug**; a department slug includes its children.
- Rules the enum gave for free are now service rules: leaf-only products, same type as parent, no delete while in use.
