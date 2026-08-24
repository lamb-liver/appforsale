CREATE UNIQUE INDEX devices_one_active_per_user_idx
ON devices(user_id) WHERE status = 'ACTIVE';

CREATE TABLE refresh_credentials (
  token_hash TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES users(id),
  device_id TEXT NOT NULL REFERENCES devices(id),
  family_id TEXT NOT NULL,
  generation INTEGER NOT NULL CHECK (generation >= 0),
  created_at_utc TEXT NOT NULL,
  expires_at_utc TEXT NOT NULL,
  revoked_at_utc TEXT,
  UNIQUE(family_id, generation)
);
CREATE INDEX refresh_credentials_user_device_idx ON refresh_credentials(user_id, device_id);

CREATE TABLE device_transfers (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES users(id),
  source_device_id TEXT NOT NULL REFERENCES devices(id),
  target_device_id TEXT,
  target_device_name TEXT,
  token_hash TEXT NOT NULL UNIQUE,
  commit_token_hash TEXT UNIQUE,
  status TEXT NOT NULL CHECK (status IN ('REQUESTED', 'CLAIMED', 'COMMITTED')),
  created_at_utc TEXT NOT NULL,
  expires_at_utc TEXT NOT NULL,
  claimed_at_utc TEXT,
  committed_at_utc TEXT
);
CREATE INDEX device_transfers_user_idx ON device_transfers(user_id, created_at_utc);
