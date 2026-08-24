CREATE TABLE deletion_tombstones (
  id TEXT PRIMARY KEY,
  google_sub TEXT NOT NULL,
  former_user_id TEXT,
  scope TEXT NOT NULL CHECK (scope IN ('CLOUD', 'ACCOUNT')),
  deletion_epoch INTEGER NOT NULL CHECK (deletion_epoch >= 0),
  requested_at_utc TEXT NOT NULL,
  completed_at_utc TEXT
);
CREATE INDEX deletion_tombstones_sub_epoch_idx ON deletion_tombstones(google_sub, deletion_epoch DESC);
CREATE TRIGGER deletion_tombstones_no_update
BEFORE UPDATE ON deletion_tombstones BEGIN
  SELECT RAISE(ABORT, 'deletion tombstones are append-only');
END;
CREATE TRIGGER deletion_tombstones_no_delete
BEFORE DELETE ON deletion_tombstones BEGIN
  SELECT RAISE(ABORT, 'deletion tombstones are append-only');
END;
