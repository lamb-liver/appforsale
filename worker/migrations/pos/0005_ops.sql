ALTER TABLE users ADD COLUMN email TEXT;

CREATE TABLE inactivity_notifications (
  user_id TEXT NOT NULL REFERENCES users(id),
  days_before_deletion INTEGER NOT NULL CHECK (days_before_deletion IN (60, 7)),
  activity_at_utc TEXT NOT NULL,
  sent_at_utc TEXT NOT NULL,
  PRIMARY KEY(user_id, days_before_deletion, activity_at_utc)
);
CREATE INDEX inactivity_notifications_sent_idx ON inactivity_notifications(sent_at_utc);
