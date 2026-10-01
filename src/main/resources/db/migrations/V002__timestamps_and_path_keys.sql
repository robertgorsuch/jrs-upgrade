-- Review findings 1.14 and 1.15.
-- Timestamps compared or ordered in SQL are rewritten fixed-width at millisecond precision
-- (Instant.toString() emitted 0, 3, 6 or 9 fractional digits, which does not sort chronologically).
-- step_transitions and audit are append-only by trigger and are ordered by seq, so they keep
-- whatever form they were written in; the reader accepts both.
-- Paths gain a canonical key (absolute, normalised, forward slashes, case-folded where the file
-- system folds case) next to the display path; the migration runner fills it from Java.

UPDATE servers SET first_seen = CASE
  WHEN length(first_seen) = 20 THEN substr(first_seen, 1, 19) || '.000Z'
  WHEN length(first_seen) > 24 THEN substr(first_seen, 1, 23) || 'Z'
  ELSE first_seen END;
UPDATE servers SET last_seen = CASE
  WHEN length(last_seen) = 20 THEN substr(last_seen, 1, 19) || '.000Z'
  WHEN length(last_seen) > 24 THEN substr(last_seen, 1, 23) || 'Z'
  ELSE last_seen END;
UPDATE hotfixes_installed SET installed_at = CASE
  WHEN length(installed_at) = 20 THEN substr(installed_at, 1, 19) || '.000Z'
  WHEN length(installed_at) > 24 THEN substr(installed_at, 1, 23) || 'Z'
  ELSE installed_at END;
UPDATE customizations SET registered_at = CASE
  WHEN length(registered_at) = 20 THEN substr(registered_at, 1, 19) || '.000Z'
  WHEN length(registered_at) > 24 THEN substr(registered_at, 1, 23) || 'Z'
  ELSE registered_at END;
UPDATE plans SET created_at = CASE
  WHEN length(created_at) = 20 THEN substr(created_at, 1, 19) || '.000Z'
  WHEN length(created_at) > 24 THEN substr(created_at, 1, 23) || 'Z'
  ELSE created_at END;
UPDATE plans SET expires_at = CASE
  WHEN length(expires_at) = 20 THEN substr(expires_at, 1, 19) || '.000Z'
  WHEN length(expires_at) > 24 THEN substr(expires_at, 1, 23) || 'Z'
  ELSE expires_at END;
UPDATE runs SET started_at = CASE
  WHEN length(started_at) = 20 THEN substr(started_at, 1, 19) || '.000Z'
  WHEN length(started_at) > 24 THEN substr(started_at, 1, 23) || 'Z'
  ELSE started_at END;
UPDATE runs SET ended_at = CASE
  WHEN ended_at IS NULL THEN NULL
  WHEN length(ended_at) = 20 THEN substr(ended_at, 1, 19) || '.000Z'
  WHEN length(ended_at) > 24 THEN substr(ended_at, 1, 23) || 'Z'
  ELSE ended_at END;

ALTER TABLE hotfix_files ADD COLUMN path_key TEXT;
CREATE INDEX hotfix_files_by_key ON hotfix_files(path_key);
ALTER TABLE customizations ADD COLUMN path_key TEXT;
CREATE INDEX customizations_by_key ON customizations(path_key);
