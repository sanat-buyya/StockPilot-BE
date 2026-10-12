-- StockPilot initial schema. All timestamps are timestamptz (stored as UTC).

CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE users (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  email         text NOT NULL,
  name          text NOT NULL,
  password_hash text NOT NULL,
  created_at    timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT users_email_lower CHECK (email = lower(email))
);
CREATE UNIQUE INDEX users_email_uq ON users (email);

CREATE TABLE workspaces (
  id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  name       text NOT NULL CHECK (length(btrim(name)) BETWEEN 1 AND 120),
  created_by uuid NOT NULL REFERENCES users(id),
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE workspace_members (
  workspace_id uuid NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
  user_id      uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  role         text NOT NULL CHECK (role IN ('owner', 'staff')),
  created_at   timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (workspace_id, user_id)
);
CREATE INDEX workspace_members_user_idx ON workspace_members (user_id);

CREATE TABLE products (
  id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  workspace_id        uuid NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
  name                text NOT NULL CHECK (length(btrim(name)) BETWEEN 1 AND 200),
  sku                 text NOT NULL CHECK (length(sku) BETWEEN 1 AND 64 AND sku = upper(btrim(sku))),
  description         text CHECK (description IS NULL OR length(description) <= 2000),
  unit_price          numeric(12,2) NOT NULL CHECK (unit_price >= 0),
  stock_quantity      integer NOT NULL DEFAULT 0 CHECK (stock_quantity >= 0),
  low_stock_threshold integer NOT NULL DEFAULT 0 CHECK (low_stock_threshold >= 0),
  is_active           boolean NOT NULL DEFAULT true,
  created_at          timestamptz NOT NULL DEFAULT now(),
  updated_at          timestamptz NOT NULL DEFAULT now(),
  -- SKU unique per workspace only; different workspaces may reuse a SKU.
  CONSTRAINT products_workspace_sku_uq UNIQUE (workspace_id, sku),
  -- Target for composite FKs so children can prove "same workspace" in the schema.
  CONSTRAINT products_id_workspace_uq UNIQUE (id, workspace_id)
);
CREATE INDEX products_workspace_name_idx ON products (workspace_id, name);

CREATE TABLE orders (
  id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  order_number      bigint GENERATED ALWAYS AS IDENTITY,
  workspace_id      uuid NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
  created_by        uuid NOT NULL REFERENCES users(id),
  status            text NOT NULL DEFAULT 'pending'
                    CHECK (status IN ('pending', 'confirmed', 'completed', 'cancelled')),
  total             numeric(14,2) NOT NULL CHECK (total >= 0),
  notes             text CHECK (notes IS NULL OR length(notes) <= 1000),
  idempotency_key   text CHECK (idempotency_key IS NULL OR length(idempotency_key) BETWEEN 8 AND 128),
  -- Set exactly once, when the reserved stock is returned. Doubles as a "restored" flag.
  stock_released_at timestamptz,
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  confirmed_at      timestamptz,
  completed_at      timestamptz,
  cancelled_at      timestamptz,
  CONSTRAINT orders_number_uq UNIQUE (order_number),
  CONSTRAINT orders_id_workspace_uq UNIQUE (id, workspace_id),
  CONSTRAINT orders_release_only_if_cancelled CHECK (stock_released_at IS NULL OR status = 'cancelled')
);
CREATE INDEX orders_workspace_created_idx ON orders (workspace_id, created_at DESC);
CREATE INDEX orders_workspace_status_idx ON orders (workspace_id, status);
CREATE UNIQUE INDEX orders_idempotency_uq ON orders (workspace_id, idempotency_key)
  WHERE idempotency_key IS NOT NULL;

CREATE TABLE order_items (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  order_id      uuid NOT NULL,
  workspace_id  uuid NOT NULL,
  product_id    uuid NOT NULL,
  -- Immutable snapshots taken when the order is created.
  product_name  text NOT NULL,
  product_sku   text NOT NULL,
  unit_price    numeric(12,2) NOT NULL CHECK (unit_price >= 0),
  quantity      integer NOT NULL CHECK (quantity > 0),
  line_total    numeric(14,2) NOT NULL CHECK (line_total >= 0),
  CONSTRAINT order_items_order_fk   FOREIGN KEY (order_id, workspace_id)   REFERENCES orders (id, workspace_id) ON DELETE CASCADE,
  CONSTRAINT order_items_product_fk FOREIGN KEY (product_id, workspace_id) REFERENCES products (id, workspace_id),
  CONSTRAINT order_items_one_per_product UNIQUE (order_id, product_id),
  CONSTRAINT order_items_total_matches CHECK (line_total = unit_price * quantity)
);

CREATE TABLE inventory_adjustments (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  workspace_id     uuid NOT NULL,
  product_id       uuid NOT NULL,
  quantity_change  integer NOT NULL CHECK (quantity_change <> 0),
  resulting_stock  integer NOT NULL CHECK (resulting_stock >= 0),
  reason_type      text NOT NULL CHECK (reason_type IN
                     ('initial_stock', 'received', 'count_correction', 'damaged', 'returned', 'other',
                      'order_reserve', 'order_release')),
  note             text CHECK (note IS NULL OR length(note) <= 500),
  order_id         uuid,
  created_by       uuid NOT NULL REFERENCES users(id),
  created_at       timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT adj_product_fk FOREIGN KEY (product_id, workspace_id) REFERENCES products (id, workspace_id),
  CONSTRAINT adj_order_fk   FOREIGN KEY (order_id, workspace_id)   REFERENCES orders (id, workspace_id),
  CONSTRAINT adj_order_reason CHECK ((order_id IS NOT NULL) = (reason_type IN ('order_reserve', 'order_release'))),
  CONSTRAINT adj_sign CHECK (
    (reason_type = 'order_reserve' AND quantity_change < 0) OR
    (reason_type = 'order_release' AND quantity_change > 0) OR
    reason_type NOT IN ('order_reserve', 'order_release'))
);
-- Backstop for "restore at most once": a second release for the same order+product is rejected by the DB.
CREATE UNIQUE INDEX adj_one_order_event_uq ON inventory_adjustments (order_id, product_id, reason_type)
  WHERE order_id IS NOT NULL;
CREATE INDEX adj_workspace_created_idx ON inventory_adjustments (workspace_id, created_at DESC);
CREATE INDEX adj_product_created_idx ON inventory_adjustments (product_id, created_at DESC);
