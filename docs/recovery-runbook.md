# jrs-upgrade recovery runbook

> jrs-upgrade was split out of jrsctl v2.3.0 (ADR-0001). ADR-0001 to ADR-0004 are this repository's own decisions (`docs/decisions/`); higher ADR numbers are jrsctl's, kept for their reasoning.

What to do when a jrs-upgrade run did not end the way it should have. Every situation below is
identified by the exit code and the message the command printed; the commands named here are
described in full in the operator guide (`jrs-upgrade docs operator-guide`, or `jrs-upgrade <command>
--explain`). Nothing in this runbook needs network access.

Start with the two commands that never change anything:

- `jrs-upgrade runs list` shows every run, its terminal state and exit code; a run without a terminal
  state is pending.
- `jrs-upgrade runs show <id>` shows the plan the run was started from, every step transition in the
  order it was journaled (`PENDING -> RUNNING -> SUCCEEDED` or `FAILED`, `ROLLED_BACK`, `SKIPPED`),
  the snapshots it took and, for a failed run, the outcome block with the cause, the affected paths,
  the backup location and the next action.

## Exit 8: "N runs need recovery before anything else can run"

A previous run has no terminal state: the process was killed, the machine lost power, or the
journal could not be written (see below). Every mutating command refuses with exit 8 until the run
is recovered, and prints the exact command.

1. `jrs-upgrade runs show <id>` and look at the last transition. `RUNNING` means the process died inside
   that step; `FAILED` means it died after the step failed but before the step was compensated.
2. `jrs-upgrade runs recover <id> --resume` re-runs the precheck of the interrupted step and then
   re-executes it. Every step is idempotent, so a step that had already done its work converges
   without doing it twice. Resume then continues with the remaining steps and ends with the run's
   normal exit code.
3. `jrs-upgrade runs recover <id> --rollback` compensates the interrupted step (whether journaled
   `RUNNING` or `FAILED`) and then every succeeded step, newest first, and exits 3. Choose this
   when the precheck of the interrupted step fails (resume then offers only rollback), when the
   operator has changed their mind, or when the step cannot be trusted to converge.
4. Export and import runs are rebuilt from their stored arguments: the archive must still be where
   it was.

Two runs cannot be pending at once, because the second could not have started.

## Exit 9: "another jrs-upgrade run holds the lock"

`runs.lock` in the jrs-upgrade home names the process and run that holds it. If that process is still
alive, wait for it or cancel it with Ctrl-C (it compensates the step in flight and exits 5). If the
process is gone, the lock is stale: `jrs-upgrade doctor` reports the lock as free (it tries the lock
rather than trusting the pid in the file), and the next mutating command simply takes it. Do not
delete `state.db`; the lock file alone is safe to remove when its holder is dead, but nothing
requires it.

## Exit 3: "run failed, rolled back"

The step named in the outcome block failed; jrs-upgrade undid that step's partial work first, then
every succeeded step back to the start of the failing phase (or the whole plan with
`--rollback-all`). The server is in the state it was in before the phase started. Read the cause,
fix what it names (a locked jar, a refused credential, a database that rejected a statement), and
run the command again. Nothing needs to be restored by hand.

An import that rolled back needs nothing by hand for the archive's folders: the rollback deletes
what the failed import created there (additions under folders that existed, and folders that did
not exist before, #100 and #139) and re-imports the pre-import snapshot for what it overwrote. It
does not track users, roles, settings or events the archive also carried, and it cannot delete an
organisation the import created (`DELETE /rest_v2/organizations/<id>`); check those. The snapshot
stays under `snapshots/pre-import/` and can be re-imported by hand with `jrs-upgrade import <snapshot>
--update`.

## Exit 4: "rollback incomplete"

A compensation failed after the run had already mutated the server, or a fatal failure happened
after a mutation. The outcome block lists the backups, always under `snapshots/<runId>/` in the
jrs-upgrade home, and the step whose compensation failed.

1. `jrs-upgrade runs show <id>` to see which steps are `ROLLED_BACK`, which is `ROLLBACK_FAILED`, and
   which succeeded steps were never compensated because the rollback stopped at the first failure.
2. Restore what the failing compensation could not: for a file step, the snapshot directory named
   in the outcome holds every touched file with its original permissions; for a SQL step, the
   bundle's rollback scripts under `runs/<id>/bundle/sql/` are the ones jrs-upgrade would have run, in
   reverse order; for a service step, stop or start the service by hand.
3. `jrs-upgrade doctor` confirms the server, the service and the configuration before anything else
   runs.
4. For an upgrade whose point-B restore failed, `jrs-upgrade upgrade rollback <runId> --to-point B`
   repeats the whole restore (webapp, buildomatic, configuration, keystore) from the recorded
   backups and refuses to start if any of them is missing or does not match its recorded hash.

## "state store ... failed PRAGMA quick_check"

`state.db` is checked with SQLite's `quick_check` every time jrs-upgrade opens it and by `doctor` (item
`state`). A result other than `ok` means the file is damaged (a full disk, a power cut during a
write, a copy taken while jrs-upgrade was running) and jrs-upgrade refuses to use it, because a journal that
cannot be trusted is worse than none. What to do:

1. Stop every jrs-upgrade process.
2. Move the file aside: `state.db` to `state.db.corrupt-<date>` (and `state.db-wal`,
   `state.db-shm` next to it, if present). Keep the copies for support.
3. Restore `state.db` from the most recent support bundle, or start with none: jrs-upgrade creates a
   fresh one on the next command. A fresh store does not know the customizations registered or
   the runs made through the damaged one; register the customizations again
   (`jrs-upgrade customizations scan --register`).
4. Run `jrs-upgrade doctor`; the `state` item must be PASS before any mutating command.

## "the run journal could not be written"

`state.db` could not take the next transition: the disk is full, the file was deleted or made
read-only, or the database is damaged. The run stops at once with exit 4 (exit 2 when nothing had
been mutated yet), says so, and names the `runs recover` command, because nothing further could
have been recorded. Free the disk or repair the permissions, run `jrs-upgrade doctor`, then recover the
run as under exit 8. If the database itself is damaged, move it aside, run `jrs-upgrade selfcheck` to
create a fresh one, and restore the server from the newest `snapshots/<runId>/` by hand; the
journal is gone with the file.

## "sc.exe stop ... exited 5: Access is denied" and similar

The service manager refused the command; jrs-upgrade did not wait for the service because waiting
could not have helped, and nothing was changed. Run jrs-upgrade with the rights the message names: an
elevated (administrator) prompt on Windows, root or a polkit or sudo rule on Linux.

## "js-upgrade-... was started in run <id> and never reported back"

The vendor upgrade script was launched and the process died before it reported an exit code, so
jrs-upgrade cannot tell whether the repository database was migrated. Read the buildomatic log under
the target package. If the upgrade did not run, delete the attempt marker the message names and
resume; if it did, `jrs-upgrade upgrade rollback <id> --to-point B`. With `--mode samedb` the database
migration is not undone by jrs-upgrade: restore the database from the backup you confirmed with
`--db-backup-confirmed` before rolling the files back.

## "passphrase does not unlock secrets.enc on this machine"

Either the passphrase is wrong or the store was created on another machine. A store created by a
build before 2026-09-10 (version 1, bound to the DNS host name) is rebound to the machine identity
automatically the first time the right passphrase unlocks it, and the log says so; if the host was
renamed before that happened, the old name is gone and the store cannot be opened: run `jrs-upgrade
secrets init` after moving the old file aside, then `jrs-upgrade secrets set` for every entry
(`jrs-upgrade secrets list` on the old file still lists the names without a passphrase).

## "keystore fingerprint mismatch: archive was exported with keystore ..."

The archive comes from a server with a different `.jrsks`; its encrypted passwords cannot be
decrypted here. Copy the source server's `.jrsks` and `.jrsksp` to this host and import again with
`--source-keystore <path> --source-keystore-password-ref <ref>`; that import always uses the
vendor tools with the service stopped, whatever `--strategy` says, and restores the current
keystore from its backup on rollback.

## "... is not one documented upgrade; the documented route is ..." (exit 2)

No single path of the compatibility matrix covers the running version and `--to`, and the route
the matrix documents stops at a version you gave no package for (ADR-0002). Nothing was changed.
Unpack the vendor distribution of each stop the message names and pass one `--package` per hop,
for example `--package <10.0.0 dir> --package <10.1.0 dir>` for 8.2.0 to 10.1.0. A package that
states no hop's version is refused with exit 1; a pair no route reaches with exit 6.

## "the transit hop to ... deployed its webapp into ..." (exit 3)

A route's intermediate hop is told `appServerType=skipAppServerCheck` and a scratch
`appServerDir`, so buildomatic should deploy nothing; this message means the live webapp states the
transit version afterwards (ADR-0002). The run was rolled back to point B: the webapp,
buildomatic, configuration and keystore are as they were. The repository database is not: the
transit hop's vendor script has run against it. Restore it with
`jrs-upgrade upgrade rollback <runId> --to-point B --restore-database` when the first hop was newdb,
or from your own backup when it was samedb, then run the hops as separate upgrades and report the
package's buildomatic version.

## "custom DDL failed: ..." (exit 3)

One of the `--custom-ddl` scripts failed against the database the newdb hop rebuilt (issue #3).
The vendor phase was rolled back to point B, but `js-upgrade-newdb` had already dropped and
recreated the repository database. Fix the script (the structure of the customer tables as they
were is in `snapshots/<runId>/foreign-objects.sql`), rebuild the old database with
`jrs-upgrade upgrade rollback <runId> --to-point B --restore-database`, then run the upgrade again.

## Hotfixes

jrs-upgrade neither applies nor rolls back hotfixes; that is jrs-hotfix's job. In a home shared
with jrsctl, jrs-upgrade never prunes the snapshots or run directories of jrsctl's runs, its hotfix
runs among them, so their rollback stays possible with jrsctl (ADR-0004).

## Getting the facts to whoever helps you

`jrs-upgrade runs support-bundle <id>`, or by hand: `jrs-upgrade runs show <id> --json`,
`jrs-upgrade doctor --json`, and `logs/jrs-upgrade.log` from the jrs-upgrade home. All three are already
redacted; none contains a secret, the keystore or an archive.
