-- Isolated staging fixtures only. Never run against stallpos-pos.
DELETE FROM sale_lines;
DELETE FROM sales;
DELETE FROM events;
DELETE FROM dashboard_sessions;
DELETE FROM sessions;
DELETE FROM devices;
DELETE FROM users;

INSERT INTO users (id, google_sub, cloud_epoch, created_at_utc)
VALUES
  ('11000000-0000-4000-8000-0000000000aa', 'google-staging-a', 1, '2026-08-26T00:00:00Z'),
  ('11000000-0000-4000-8000-0000000000bb', 'google-staging-b', 1, '2026-08-26T00:00:00Z');

INSERT INTO devices (id, user_id, short_code, name, status, cloud_epoch, registered_at_utc, last_seen_at_utc)
VALUES
  ('20000000-0000-4000-8000-0000000000aa', '11000000-0000-4000-8000-0000000000aa', 'A', 'Staging A', 'ACTIVE', 1, '2026-08-26T00:00:00Z', '2026-08-26T00:00:00Z'),
  ('20000000-0000-4000-8000-0000000000bb', '11000000-0000-4000-8000-0000000000bb', 'B', 'Staging B', 'ACTIVE', 1, '2026-08-26T00:00:00Z', '2026-08-26T00:00:00Z');

INSERT INTO sessions (token_hash, user_id, device_id, created_at_utc, expires_at_utc)
VALUES
  ('d904447cff749e6274e13488b5d3845cc4a84b3e01aa55d7c56c389f296fe8fc', '11000000-0000-4000-8000-0000000000aa', '20000000-0000-4000-8000-0000000000aa', '2026-08-26T00:00:00Z', '2099-01-01T00:00:00Z'),
  ('6a5fdd358efaee79865700f3573faa61fe0ea9914a93af1b5409a1db0e32d669', '11000000-0000-4000-8000-0000000000bb', '20000000-0000-4000-8000-0000000000bb', '2026-08-26T00:00:00Z', '2099-01-01T00:00:00Z'),
  ('5c02e55e8aa3dbad3095ed09f0043720e1d578e04c40411f8afa3d3fb1b3a9bc', '11000000-0000-4000-8000-0000000000aa', '20000000-0000-4000-8000-0000000000aa', '2026-08-26T00:00:00Z', '2020-01-01T00:00:00Z');

INSERT INTO dashboard_sessions (token_hash, user_id, created_at_utc, expires_at_utc)
VALUES
  ('54052394b1d85194c63b3a63bf046d4ac8c45cca16d7b6401630cfb9b9209206', '11000000-0000-4000-8000-0000000000aa', '2026-08-26T00:00:00Z', '2099-01-01T00:00:00Z'),
  ('5f04be923daabafccca3753c80b8a2c68937f8982c2e52ecaac1e5cb39f00306', '11000000-0000-4000-8000-0000000000bb', '2026-08-26T00:00:00Z', '2099-01-01T00:00:00Z');

INSERT INTO events (user_id, id, name, code, event_type, start_at_utc, end_at_utc, timezone, location, status, updated_at_utc, payload_json)
VALUES
  ('11000000-0000-4000-8000-0000000000aa', '70000000-0000-4000-8000-0000000000aa', 'Staging A 市集', 'STGA', 'MARKET', '2026-08-26T00:00:00Z', '2026-08-27T00:00:00Z', 'Asia/Taipei', '台北', 'CLOSED', '2026-08-26T00:00:00Z', '{}'),
  ('11000000-0000-4000-8000-0000000000bb', '70000000-0000-4000-8000-0000000000bb', 'Staging B 市集', 'STGB', 'MARKET', '2026-08-26T00:00:00Z', '2026-08-27T00:00:00Z', 'Asia/Taipei', '台北', 'CLOSED', '2026-08-26T00:00:00Z', '{}');

INSERT INTO sales (user_id, id, device_id, event_id, receipt_number, occurred_at_utc, subtotal, discount_amount, net_adjustment, final_total, tip_amount, payment_method, payload_json)
VALUES
  ('11000000-0000-4000-8000-0000000000aa', '90000000-0000-4000-8000-0000000000aa', '20000000-0000-4000-8000-0000000000aa', '70000000-0000-4000-8000-0000000000aa', 'STGA-A-0001', '2026-08-26T01:00:00Z', 100, 0, 0, 100, 0, 'CASH', '{}'),
  ('11000000-0000-4000-8000-0000000000bb', '90000000-0000-4000-8000-0000000000bb', '20000000-0000-4000-8000-0000000000bb', '70000000-0000-4000-8000-0000000000bb', 'STGB-B-0001', '2026-08-26T01:00:00Z', 200, 0, 0, 200, 0, 'CASH', '{}');
