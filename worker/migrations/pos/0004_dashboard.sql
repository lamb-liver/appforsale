CREATE TABLE dashboard_sessions (
  token_hash TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES users(id),
  created_at_utc TEXT NOT NULL,
  expires_at_utc TEXT NOT NULL
);
CREATE INDEX dashboard_sessions_user_idx ON dashboard_sessions(user_id);

CREATE INDEX inventory_levels_user_event_idx ON inventory_levels(user_id, event_id, product_id);
CREATE INDEX sale_lines_user_sale_item_idx ON sale_lines(user_id, sale_id, item_type, item_ref_id);
