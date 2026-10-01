-- jrs-upgrade state store, initial schema (spec 5.4).
-- Statements are separated by ';' at top level; CREATE TRIGGER bodies (BEGIN ... END) are kept whole.

CREATE TABLE servers (
  id          TEXT PRIMARY KEY,
  base_url    TEXT NOT NULL,
  version     TEXT NOT NULL,
  edition     TEXT NOT NULL,
  tenancy     TEXT NOT NULL,
  first_seen  TEXT NOT NULL,
  last_seen   TEXT NOT NULL
);

CREATE TABLE hotfixes_installed (
  id               TEXT PRIMARY KEY,
  version          TEXT NOT NULL,
  title            TEXT NOT NULL,
  installed_run_id TEXT NOT NULL,
  snapshot_ref     TEXT,
  state            TEXT NOT NULL CHECK (state IN ('INSTALLED', 'ROLLED_BACK', 'SUPERSEDED')),
  installed_at     TEXT NOT NULL
);

CREATE TABLE hotfix_files (
  hotfix_id     TEXT NOT NULL REFERENCES hotfixes_installed(id),
  path          TEXT NOT NULL,
  action        TEXT NOT NULL,
  before_sha256 TEXT,
  after_sha256  TEXT,
  PRIMARY KEY (hotfix_id, path)
);

CREATE INDEX hotfix_files_by_path ON hotfix_files(path);

CREATE TABLE customizations (
  path            TEXT PRIMARY KEY,
  original_sha256 TEXT NOT NULL,
  snapshot_ref    TEXT,
  registered_at   TEXT NOT NULL
);

CREATE TABLE plans (
  plan_id            TEXT PRIMARY KEY,
  operation          TEXT NOT NULL,
  args_json          TEXT NOT NULL,
  plan_json          TEXT NOT NULL,
  fingerprint        TEXT NOT NULL,
  created_at         TEXT NOT NULL,
  expires_at         TEXT NOT NULL,
  consumed_by_run_id TEXT
);

CREATE TABLE runs (
  run_id         TEXT PRIMARY KEY,
  operation      TEXT NOT NULL,
  plan_id        TEXT,
  started_at     TEXT NOT NULL,
  ended_at       TEXT,
  terminal_state TEXT CHECK (terminal_state IS NULL OR terminal_state IN
                   ('SUCCEEDED', 'ROLLED_BACK', 'FAILED', 'CANCELLED', 'PRECHECK_FAILED')),
  exit_code      INTEGER
);

CREATE TABLE step_transitions (
  seq        INTEGER PRIMARY KEY AUTOINCREMENT,
  ts         TEXT NOT NULL,
  run_id     TEXT NOT NULL REFERENCES runs(run_id),
  step_id    TEXT NOT NULL,
  phase      TEXT NOT NULL,
  from_state TEXT,
  to_state   TEXT NOT NULL,
  detail     TEXT
);

CREATE INDEX step_transitions_by_run ON step_transitions(run_id, seq);

CREATE TRIGGER step_transitions_no_update BEFORE UPDATE ON step_transitions
BEGIN
  SELECT RAISE(ABORT, 'step_transitions is append-only');
END;

CREATE TRIGGER step_transitions_no_delete BEFORE DELETE ON step_transitions
BEGIN
  SELECT RAISE(ABORT, 'step_transitions is append-only');
END;

CREATE TABLE snapshots (
  id              TEXT PRIMARY KEY,
  run_id          TEXT NOT NULL,
  step_id         TEXT NOT NULL,
  path            TEXT NOT NULL,
  manifest_sha256 TEXT NOT NULL,
  referenced_by   TEXT
);

CREATE INDEX snapshots_by_run ON snapshots(run_id);

CREATE TABLE audit (
  seq    INTEGER PRIMARY KEY AUTOINCREMENT,
  ts     TEXT NOT NULL,
  actor  TEXT NOT NULL,
  action TEXT NOT NULL,
  detail TEXT
);

CREATE TRIGGER audit_no_update BEFORE UPDATE ON audit
BEGIN
  SELECT RAISE(ABORT, 'audit is append-only');
END;

CREATE TRIGGER audit_no_delete BEFORE DELETE ON audit
BEGIN
  SELECT RAISE(ABORT, 'audit is append-only');
END;
