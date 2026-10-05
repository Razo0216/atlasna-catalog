-- =====================================================================
-- V2: re-shape the generic catalog into a GROCERY + BEAUTY mini-mart catalog
--
-- DESTRUCTIVE for `products`: removes the old demo rows (electronics, fashion,
-- ...). That is only acceptable because nothing real exists yet. Once real
-- data exists, never write a migration like this - migrate the data instead.
-- =====================================================================

DELETE FROM products;

-- ---------- users: contact phone (needed for delivery) ----------
-- Stored normalised as +213XXXXXXXXX. Nullable at DB level so legacy/admin rows
-- stay valid; the API makes it mandatory for customers.
ALTER TABLE users ADD COLUMN phone VARCHAR(16);
CREATE UNIQUE INDEX ux_users_phone ON users (phone) WHERE phone IS NOT NULL;

-- ---------- categories: an admin-editable two-level tree ----------
CREATE TABLE categories (
  id          BIGSERIAL PRIMARY KEY,
  parent_id   BIGINT REFERENCES categories (id),
  slug        VARCHAR(100) NOT NULL,
  type        VARCHAR(10)  NOT NULL,
  name_fr     VARCHAR(150) NOT NULL,
  name_ar     VARCHAR(150),
  sort_order  INTEGER      NOT NULL DEFAULT 0,
  active      BOOLEAN      NOT NULL DEFAULT TRUE,
  created_at  TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT now(),
  updated_at  TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT now(),
  CONSTRAINT ck_categories_type CHECK (type IN ('GROCERY', 'BEAUTY'))
);
CREATE UNIQUE INDEX ux_categories_slug ON categories (slug);
CREATE INDEX ix_categories_parent ON categories (parent_id);

INSERT INTO categories (parent_id, slug, type, name_fr, name_ar, sort_order) VALUES
  (NULL, 'epicerie', 'GROCERY', 'Épicerie',  'البقالة', 1),
  (NULL, 'beaute',   'BEAUTY',  'Beauté',    'الجمال',  2);

INSERT INTO categories (parent_id, slug, type, name_fr, name_ar, sort_order)
SELECT p.id, v.slug, p.type, v.name_fr, v.name_ar, v.sort_order
FROM (VALUES
  ('epicerie', 'fruits-legumes',          'Fruits & légumes',                  'فواكه وخضروات',               1),
  ('epicerie', 'produits-laitiers-oeufs', 'Produits laitiers & œufs',          'منتجات الألبان والبيض',        2),
  ('epicerie', 'boulangerie-patisserie',  'Boulangerie & pâtisserie',          'مخبوزات وحلويات',              3),
  ('epicerie', 'epicerie-salee',          'Épicerie salée (pâtes, semoule, riz, conserves)', 'مواد غذائية أساسية', 4),
  ('epicerie', 'epicerie-sucree',         'Épicerie sucrée & petit-déjeuner',  'مواد سكرية وفطور',             5),
  ('epicerie', 'huiles-condiments-epices','Huiles, condiments & épices',       'زيوت وتوابل وبهارات',          6),
  ('epicerie', 'boissons',                'Boissons (jus, café, thé, eau)',    'مشروبات',                      7),
  ('epicerie', 'snacks-confiseries',      'Snacks & confiseries',              'وجبات خفيفة وحلويات',          8),
  ('beaute',   'soins-visage',            'Soins du visage',                   'العناية بالوجه',               1),
  ('beaute',   'soins-corps',             'Soins du corps',                    'العناية بالجسم',               2),
  ('beaute',   'cheveux',                 'Cheveux',                           'الشعر',                        3),
  ('beaute',   'maquillage',              'Maquillage',                        'المكياج',                      4),
  ('beaute',   'parfums-deodorants',      'Parfums & déodorants',              'العطور ومزيلات العرق',         5),
  ('beaute',   'hygiene-personnelle',     'Hygiène personnelle',               'النظافة الشخصية',              6),
  ('beaute',   'naturel-traditionnel',    'Soins naturels & traditionnels (argan, henné, rose)', 'العناية الطبيعية والتقليدية', 7)
) AS v (parent_slug, slug, name_fr, name_ar, sort_order)
JOIN categories p ON p.slug = v.parent_slug;

-- ---------- products ----------
ALTER TABLE products RENAME COLUMN name        TO name_fr;
ALTER TABLE products RENAME COLUMN description TO description_fr;
ALTER TABLE products DROP COLUMN category;                       -- enum string -> category_id
ALTER TABLE products ALTER COLUMN stock_quantity TYPE NUMERIC(10,3); -- kg can be fractional
ALTER TABLE products ALTER COLUMN stock_quantity SET DEFAULT 0;

ALTER TABLE products
  ADD COLUMN name_ar             VARCHAR(255),
  ADD COLUMN description_ar      VARCHAR(2000),
  ADD COLUMN slug                VARCHAR(220)  NOT NULL,
  ADD COLUMN sku                 VARCHAR(40)   NOT NULL,
  ADD COLUMN barcode             VARCHAR(14),
  ADD COLUMN brand               VARCHAR(100),
  ADD COLUMN category_id         BIGINT        NOT NULL REFERENCES categories (id),
  ADD COLUMN sold_by             VARCHAR(10)   NOT NULL,           -- UNIT | WEIGHT (price is per piece, or per kg)
  ADD COLUMN quantity_step       NUMERIC(6,3)  NOT NULL DEFAULT 1, -- 1 for pieces, 0.5 for loose produce
  ADD COLUMN unit_weight_grams   INTEGER       NOT NULL,           -- weight of ONE sold unit (1000 for per-kg items)
  ADD COLUMN low_stock_threshold NUMERIC(10,3) NOT NULL DEFAULT 5,
  ADD COLUMN perishable          BOOLEAN       NOT NULL DEFAULT FALSE,
  ADD COLUMN featured            BOOLEAN       NOT NULL DEFAULT FALSE,
  ADD COLUMN compare_at_price    NUMERIC(12,2),                    -- "was" price for promotions
  ADD COLUMN attributes          JSONB         NOT NULL DEFAULT '{}'::jsonb, -- halal, origin, skinType, ingredients...
  ADD COLUMN version             BIGINT        NOT NULL DEFAULT 0; -- optimistic locking (stock concurrency, Phase 3)

ALTER TABLE products
  ADD CONSTRAINT ck_products_price_positive  CHECK (price > 0),
  ADD CONSTRAINT ck_products_stock_non_neg   CHECK (stock_quantity >= 0),
  ADD CONSTRAINT ck_products_sold_by         CHECK (sold_by IN ('UNIT', 'WEIGHT')),
  ADD CONSTRAINT ck_products_step_positive   CHECK (quantity_step > 0),
  ADD CONSTRAINT ck_products_weight_positive CHECK (unit_weight_grams > 0),
  ADD CONSTRAINT ck_products_compare_price   CHECK (compare_at_price IS NULL OR compare_at_price > price);

CREATE UNIQUE INDEX ux_products_slug    ON products (slug);
CREATE UNIQUE INDEX ux_products_sku     ON products (sku);
CREATE UNIQUE INDEX ux_products_barcode ON products (barcode) WHERE barcode IS NOT NULL;
CREATE INDEX ix_products_category_active ON products (category_id) WHERE active;
CREATE INDEX ix_products_featured        ON products (id) WHERE active AND featured;

CREATE TABLE product_images (
  id          BIGSERIAL PRIMARY KEY,
  product_id  BIGINT       NOT NULL REFERENCES products (id) ON DELETE CASCADE,
  storage_key VARCHAR(300) NOT NULL,   -- object key (Phase 5 -> Cloud Storage); public URL is derived, never stored
  alt_fr      VARCHAR(200),
  alt_ar      VARCHAR(200),
  sort_order  INTEGER      NOT NULL DEFAULT 0,
  created_at  TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX ix_product_images_product ON product_images (product_id, sort_order);
