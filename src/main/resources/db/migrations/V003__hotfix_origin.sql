-- ADR-0030 (issue #99): a hotfix applied by hand can be recorded in the ledger from the
-- package's readme. Such a row owns no files and no snapshot, so it cannot be rolled back; the
-- origin column tells the two kinds apart. Every row written before this version was applied by
-- jrs-upgrade itself.
ALTER TABLE hotfixes_installed ADD COLUMN origin TEXT NOT NULL DEFAULT 'JRSCTL';
