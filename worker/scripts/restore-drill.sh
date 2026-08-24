#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "$0")" && pwd)"
temp_root="${TMPDIR:-/tmp}"
temp_root="${temp_root%/}"
drill_dir="$(mktemp -d)"
cleanup() {
  case "$drill_dir" in
    "$temp_root"/tmp.*|/tmp/tmp.*) rm -r -- "$drill_dir" ;;
    *) printf 'Refusing to remove unexpected path: %s\n' "$drill_dir" >&2 ;;
  esac
}
trap cleanup EXIT

pos_db="$drill_dir/pos.sqlite"
pos_before_delete="$drill_dir/pos-before-delete.sqlite"
deletion_db="$drill_dir/deletion.sqlite"

for migration in "$script_dir"/../migrations/pos/*.sql; do sqlite3 "$pos_db" < "$migration"; done
for migration in "$script_dir"/../migrations/deletion/*.sql; do sqlite3 "$deletion_db" < "$migration"; done

sqlite3 "$pos_db" <<'SQL'
PRAGMA foreign_keys=ON;
INSERT INTO users (id,google_sub,cloud_epoch,created_at_utc,email) VALUES
 ('old-account','google-old-account',1,'2026-08-01T00:00:00Z','old-account@example.com'),
 ('old-cloud','google-old-cloud',1,'2026-08-01T00:00:00Z','old-cloud@example.com'),
 ('new-generation','google-reenabled',2,'2026-08-22T00:00:00Z','new@example.com');
INSERT INTO devices (id,user_id,short_code,name,status,cloud_epoch,registered_at_utc,last_seen_at_utc) VALUES
 ('device-account','old-account','A','Old account','ACTIVE',1,'2026-08-01T00:00:00Z','2026-08-20T00:00:00Z'),
 ('device-cloud','old-cloud','A','Old cloud','ACTIVE',1,'2026-08-01T00:00:00Z','2026-08-20T00:00:00Z'),
 ('device-new','new-generation','A','New generation','ACTIVE',2,'2026-08-22T00:00:00Z','2026-08-24T00:00:00Z');
INSERT INTO sessions (token_hash,user_id,device_id,created_at_utc,expires_at_utc) VALUES
 ('session-account','old-account','device-account','2026-08-01T00:00:00Z','2027-01-01T00:00:00Z'),
 ('session-cloud','old-cloud','device-cloud','2026-08-01T00:00:00Z','2027-01-01T00:00:00Z'),
 ('session-new','new-generation','device-new','2026-08-22T00:00:00Z','2027-01-01T00:00:00Z');
INSERT INTO products (user_id,id,name,selling_price,cost,track_inventory,is_active,updated_at_utc,payload_json) VALUES
 ('old-account','product-account','Old product',100,NULL,1,1,'2026-08-20T00:00:00Z','{}'),
 ('old-cloud','product-cloud','Old product',100,NULL,1,1,'2026-08-20T00:00:00Z','{}'),
 ('new-generation','product-new','New product',100,NULL,1,1,'2026-08-24T00:00:00Z','{}');
SQL
cp "$pos_db" "$pos_before_delete"

sqlite3 "$deletion_db" <<'SQL'
INSERT INTO deletion_tombstones (id,google_sub,former_user_id,scope,deletion_epoch,requested_at_utc) VALUES
 ('tomb-account','google-old-account','old-account','ACCOUNT',1,'2026-08-21T00:00:00Z'),
 ('tomb-cloud','google-old-cloud','old-cloud','CLOUD',1,'2026-08-21T00:00:00Z'),
 ('tomb-before-new','google-reenabled','previous-generation','ACCOUNT',1,'2026-08-21T00:00:00Z');
SQL

# Simulate the completed deletion, then a POS_DB-only restore to the pre-deletion snapshot.
sqlite3 "$pos_db" "PRAGMA foreign_keys=ON; DELETE FROM sessions WHERE user_id IN ('old-account','old-cloud'); DELETE FROM devices WHERE user_id IN ('old-account','old-cloud'); DELETE FROM products WHERE user_id IN ('old-account','old-cloud'); DELETE FROM users WHERE id='old-account'; UPDATE users SET deleted_at_utc='2026-08-21T00:00:00Z' WHERE id='old-cloud';"
cp "$pos_before_delete" "$pos_db"

# Reconcile against the untouched DELETION_DB. This mirrors clearUserStatements and the generation guards.
sqlite3 "$pos_db" <<SQL
PRAGMA foreign_keys=ON;
ATTACH DATABASE '$deletion_db' AS deletion;
BEGIN IMMEDIATE;
CREATE TEMP TABLE restore_barrier AS
SELECT u.id,t.scope,t.requested_at_utc
FROM users u JOIN deletion.deletion_tombstones t ON t.google_sub=u.google_sub
WHERE u.created_at_utc<=t.requested_at_utc
  AND (t.scope='ACCOUNT' OR u.cloud_epoch<=t.deletion_epoch);
DELETE FROM audit_logs WHERE user_id IN (SELECT id FROM restore_barrier);
DELETE FROM processed_operations WHERE user_id IN (SELECT id FROM restore_barrier);
DELETE FROM voids WHERE user_id IN (SELECT id FROM restore_barrier);
DELETE FROM bundle_component_allocations WHERE user_id IN (SELECT id FROM restore_barrier);
DELETE FROM sale_lines WHERE user_id IN (SELECT id FROM restore_barrier);
DELETE FROM sales WHERE user_id IN (SELECT id FROM restore_barrier);
DELETE FROM inventory_movements WHERE user_id IN (SELECT id FROM restore_barrier);
DELETE FROM inventory_levels WHERE user_id IN (SELECT id FROM restore_barrier);
DELETE FROM events WHERE user_id IN (SELECT id FROM restore_barrier);
DELETE FROM bundles WHERE user_id IN (SELECT id FROM restore_barrier);
DELETE FROM products WHERE user_id IN (SELECT id FROM restore_barrier);
DELETE FROM categories WHERE user_id IN (SELECT id FROM restore_barrier);
DELETE FROM inactivity_notifications WHERE user_id IN (SELECT id FROM restore_barrier);
DELETE FROM dashboard_sessions WHERE user_id IN (SELECT id FROM restore_barrier);
DELETE FROM device_transfers WHERE user_id IN (SELECT id FROM restore_barrier);
DELETE FROM refresh_credentials WHERE user_id IN (SELECT id FROM restore_barrier);
DELETE FROM sessions WHERE user_id IN (SELECT id FROM restore_barrier);
DELETE FROM devices WHERE user_id IN (SELECT id FROM restore_barrier);
UPDATE users SET deleted_at_utc=(SELECT requested_at_utc FROM restore_barrier WHERE id=users.id),email=NULL
 WHERE id IN (SELECT id FROM restore_barrier WHERE scope='CLOUD');
DELETE FROM users WHERE id IN (SELECT id FROM restore_barrier WHERE scope='ACCOUNT');
COMMIT;
SQL

test "$(sqlite3 "$pos_db" "SELECT COUNT(*) FROM users WHERE id='old-account';")" = "0"
test "$(sqlite3 "$pos_db" "SELECT COUNT(*) FROM users WHERE id='old-cloud' AND deleted_at_utc IS NOT NULL AND email IS NULL;")" = "1"
test "$(sqlite3 "$pos_db" "SELECT COUNT(*) FROM sessions WHERE user_id IN ('old-account','old-cloud');")" = "0"
test "$(sqlite3 "$pos_db" "SELECT COUNT(*) FROM products WHERE user_id IN ('old-account','old-cloud');")" = "0"
test "$(sqlite3 "$pos_db" "SELECT COUNT(*) FROM users WHERE id='new-generation' AND deleted_at_utc IS NULL;")" = "1"
test "$(sqlite3 "$pos_db" "SELECT COUNT(*) FROM sessions WHERE user_id='new-generation';")" = "1"
test "$(sqlite3 "$pos_db" "SELECT COUNT(*) FROM products WHERE user_id='new-generation';")" = "1"
test "$(sqlite3 "$deletion_db" "SELECT COUNT(*) FROM deletion_tombstones;")" = "3"

printf 'Restore drill passed: restored deletions removed, newer generation preserved, DELETION_DB unchanged.\n'
