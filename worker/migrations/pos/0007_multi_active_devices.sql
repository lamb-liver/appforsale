-- ponytail: drop one-ACTIVE lock. Cap is auth.ts MAX_ACTIVE_DEVICES, not a DB constraint.
DROP INDEX IF EXISTS devices_one_active_per_user_idx;
