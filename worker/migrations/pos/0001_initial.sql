PRAGMA foreign_keys = ON;

CREATE TABLE users (
  id TEXT PRIMARY KEY,
  google_sub TEXT NOT NULL UNIQUE,
  cloud_epoch INTEGER NOT NULL DEFAULT 1 CHECK (cloud_epoch >= 0),
  created_at_utc TEXT NOT NULL,
  deleted_at_utc TEXT
);

CREATE TABLE sessions (
  token_hash TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES users(id),
  device_id TEXT NOT NULL,
  created_at_utc TEXT NOT NULL,
  expires_at_utc TEXT NOT NULL,
  revoked_at_utc TEXT
);
CREATE INDEX sessions_user_id_idx ON sessions(user_id);

CREATE TABLE devices (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES users(id),
  short_code TEXT NOT NULL,
  name TEXT NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('ACTIVE', 'RETIRED')),
  cloud_epoch INTEGER NOT NULL CHECK (cloud_epoch >= 0),
  registered_at_utc TEXT NOT NULL,
  last_seen_at_utc TEXT NOT NULL,
  retired_at_utc TEXT,
  UNIQUE(user_id, short_code)
);
CREATE INDEX devices_user_id_idx ON devices(user_id);

CREATE TABLE categories (
  user_id TEXT NOT NULL REFERENCES users(id),
  id TEXT NOT NULL,
  category_type TEXT NOT NULL,
  name TEXT NOT NULL,
  sort_order INTEGER NOT NULL,
  updated_at_utc TEXT NOT NULL,
  deleted_at_utc TEXT,
  payload_json TEXT NOT NULL,
  PRIMARY KEY(user_id, id)
);

CREATE TABLE products (
  user_id TEXT NOT NULL REFERENCES users(id),
  id TEXT NOT NULL,
  name TEXT NOT NULL,
  selling_price INTEGER NOT NULL CHECK (selling_price >= 0),
  cost INTEGER CHECK (cost IS NULL OR cost >= 0),
  category_id TEXT,
  track_inventory INTEGER NOT NULL CHECK (track_inventory IN (0, 1)),
  is_active INTEGER NOT NULL CHECK (is_active IN (0, 1)),
  updated_at_utc TEXT NOT NULL,
  deleted_at_utc TEXT,
  payload_json TEXT NOT NULL,
  PRIMARY KEY(user_id, id)
);
CREATE INDEX products_user_category_idx ON products(user_id, category_id);

CREATE TABLE bundles (
  user_id TEXT NOT NULL REFERENCES users(id),
  id TEXT NOT NULL,
  name TEXT NOT NULL,
  selling_price INTEGER NOT NULL CHECK (selling_price >= 0),
  category_id TEXT,
  is_active INTEGER NOT NULL CHECK (is_active IN (0, 1)),
  components_json TEXT NOT NULL,
  updated_at_utc TEXT NOT NULL,
  deleted_at_utc TEXT,
  payload_json TEXT NOT NULL,
  PRIMARY KEY(user_id, id)
);
CREATE INDEX bundles_user_category_idx ON bundles(user_id, category_id);

CREATE TABLE events (
  user_id TEXT NOT NULL REFERENCES users(id),
  id TEXT NOT NULL,
  name TEXT NOT NULL,
  code TEXT NOT NULL,
  event_type TEXT NOT NULL,
  start_at_utc TEXT NOT NULL,
  end_at_utc TEXT NOT NULL,
  actual_open_at_utc TEXT,
  actual_close_at_utc TEXT,
  timezone TEXT NOT NULL,
  location TEXT NOT NULL,
  status TEXT NOT NULL,
  updated_at_utc TEXT NOT NULL,
  payload_json TEXT NOT NULL,
  PRIMARY KEY(user_id, id),
  UNIQUE(user_id, code)
);
CREATE INDEX events_user_status_idx ON events(user_id, status);

CREATE TABLE inventory_levels (
  user_id TEXT NOT NULL REFERENCES users(id),
  product_id TEXT NOT NULL,
  location_key TEXT NOT NULL,
  location_type TEXT NOT NULL,
  event_id TEXT,
  quantity INTEGER NOT NULL CHECK (quantity >= 0),
  updated_at_utc TEXT NOT NULL,
  PRIMARY KEY(user_id, product_id, location_key)
);

CREATE TABLE inventory_movements (
  user_id TEXT NOT NULL REFERENCES users(id),
  id TEXT NOT NULL,
  product_id TEXT NOT NULL,
  event_id TEXT,
  from_location_key TEXT,
  to_location_key TEXT,
  movement_type TEXT NOT NULL,
  quantity INTEGER NOT NULL CHECK (quantity > 0),
  related_transaction_id TEXT,
  occurred_at_utc TEXT NOT NULL,
  payload_json TEXT NOT NULL,
  PRIMARY KEY(user_id, id)
);
CREATE INDEX inventory_movements_user_product_idx ON inventory_movements(user_id, product_id, occurred_at_utc);
CREATE INDEX inventory_movements_user_event_idx ON inventory_movements(user_id, event_id, occurred_at_utc);
CREATE TRIGGER inventory_movements_source_guard
BEFORE INSERT ON inventory_movements
WHEN NEW.from_location_key IS NOT NULL AND NOT EXISTS (
  SELECT 1 FROM inventory_levels
  WHERE user_id = NEW.user_id
    AND product_id = NEW.product_id
    AND location_key = NEW.from_location_key
    AND quantity >= NEW.quantity
)
BEGIN
  SELECT RAISE(ABORT, 'inventory is insufficient');
END;
CREATE TRIGGER inventory_movements_apply_source
AFTER INSERT ON inventory_movements
WHEN NEW.from_location_key IS NOT NULL
BEGIN
  UPDATE inventory_levels
  SET quantity = quantity - NEW.quantity, updated_at_utc = NEW.occurred_at_utc
  WHERE user_id = NEW.user_id
    AND product_id = NEW.product_id
    AND location_key = NEW.from_location_key;
END;
CREATE TRIGGER inventory_movements_apply_destination
AFTER INSERT ON inventory_movements
WHEN NEW.to_location_key IS NOT NULL
BEGIN
  INSERT INTO inventory_levels (
    user_id, product_id, location_key, location_type, event_id, quantity, updated_at_utc
  ) VALUES (
    NEW.user_id,
    NEW.product_id,
    NEW.to_location_key,
    CASE WHEN NEW.to_location_key = 'GENERAL' THEN 'GENERAL' ELSE 'EVENT' END,
    CASE WHEN NEW.to_location_key = 'GENERAL' THEN NULL ELSE NEW.event_id END,
    NEW.quantity,
    NEW.occurred_at_utc
  ) ON CONFLICT(user_id, product_id, location_key) DO UPDATE SET
    quantity = quantity + NEW.quantity,
    updated_at_utc = NEW.occurred_at_utc;
END;

CREATE TABLE sales (
  user_id TEXT NOT NULL REFERENCES users(id),
  id TEXT NOT NULL,
  device_id TEXT NOT NULL,
  event_id TEXT,
  receipt_number TEXT NOT NULL,
  occurred_at_utc TEXT NOT NULL,
  subtotal INTEGER NOT NULL,
  discount_amount INTEGER NOT NULL,
  net_adjustment INTEGER NOT NULL,
  final_total INTEGER NOT NULL,
  tip_amount INTEGER NOT NULL,
  payment_method TEXT NOT NULL,
  payload_json TEXT NOT NULL,
  PRIMARY KEY(user_id, id),
  UNIQUE(user_id, receipt_number)
);
CREATE INDEX sales_user_event_time_idx ON sales(user_id, event_id, occurred_at_utc);

CREATE TABLE sale_lines (
  user_id TEXT NOT NULL,
  sale_id TEXT NOT NULL,
  line_index INTEGER NOT NULL,
  item_type TEXT NOT NULL,
  item_ref_id TEXT NOT NULL,
  display_name TEXT NOT NULL,
  quantity INTEGER NOT NULL,
  unit_price INTEGER NOT NULL,
  original_amount INTEGER NOT NULL,
  allocated_discount INTEGER NOT NULL,
  allocated_adjustment INTEGER NOT NULL,
  final_amount INTEGER NOT NULL,
  PRIMARY KEY(user_id, sale_id, line_index),
  FOREIGN KEY(user_id, sale_id) REFERENCES sales(user_id, id)
);
CREATE INDEX sale_lines_user_item_idx ON sale_lines(user_id, item_type, item_ref_id);

CREATE TABLE bundle_component_allocations (
  user_id TEXT NOT NULL,
  sale_id TEXT NOT NULL,
  line_index INTEGER NOT NULL,
  allocation_index INTEGER NOT NULL,
  product_id TEXT NOT NULL,
  product_name_snapshot TEXT NOT NULL,
  unit_cost_snapshot INTEGER,
  quantity INTEGER NOT NULL,
  allocated_revenue INTEGER NOT NULL,
  PRIMARY KEY(user_id, sale_id, line_index, allocation_index),
  FOREIGN KEY(user_id, sale_id) REFERENCES sales(user_id, id)
);
CREATE INDEX allocations_user_product_idx ON bundle_component_allocations(user_id, product_id);

CREATE TABLE voids (
  user_id TEXT NOT NULL REFERENCES users(id),
  id TEXT NOT NULL,
  sale_id TEXT NOT NULL,
  device_id TEXT NOT NULL,
  event_id TEXT,
  occurred_at_utc TEXT NOT NULL,
  reason TEXT NOT NULL,
  payment_method TEXT NOT NULL,
  payload_json TEXT NOT NULL,
  PRIMARY KEY(user_id, id),
  UNIQUE(user_id, sale_id),
  FOREIGN KEY(user_id, sale_id) REFERENCES sales(user_id, id)
);
CREATE INDEX voids_user_event_time_idx ON voids(user_id, event_id, occurred_at_utc);

CREATE TABLE processed_operations (
  user_id TEXT NOT NULL REFERENCES users(id),
  operation_id TEXT NOT NULL,
  payload_hash TEXT NOT NULL,
  entity_type TEXT NOT NULL,
  entity_id TEXT NOT NULL,
  processed_at_utc TEXT NOT NULL,
  PRIMARY KEY(user_id, operation_id)
);

CREATE TABLE audit_logs (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES users(id),
  device_id TEXT,
  request_id TEXT NOT NULL,
  operation_id TEXT,
  action TEXT NOT NULL,
  entity_type TEXT,
  entity_id TEXT,
  occurred_at_utc TEXT NOT NULL
);
CREATE INDEX audit_logs_user_time_idx ON audit_logs(user_id, occurred_at_utc);
