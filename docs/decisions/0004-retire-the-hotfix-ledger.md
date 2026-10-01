# 0004: The hotfix ledger is retired; its tables stay, unread

**Context.** ADR-0001 cut the hotfix subsystem but kept jrsctl's hotfix ledger: the `hotfixes_installed` and `hotfix_files` tables, their readers and `StateStore`'s methods for them. The schema is versioned and shared with homes jrsctl wrote, and `home set` refused to move a home that held an installed hotfix. Since then jrs-hotfix has dropped its own ledger, and jrs-upgrade never writes this one. So the only rows it can read are jrsctl's, in a home the two tools share. Five places still read or wrote it:

- `HotfixLabels` (#9) reported recorded hotfixes whose labels differ from the running version;
- `record-upgrade` marked recorded hotfixes `SUPERSEDED`, and its compensation put them back;
- `RetentionProtection` kept the snapshots of every installed hotfix's run;
- `home set` and `home reset` refused while a hotfix was installed;
- several messages pointed to `jrs-upgrade hotfix list`, a command this tool does not have.

**Decision** (issue #20).

- **The tables stay; nothing uses them.** No migration is added. `V001`–`V003` stay as written, since applied migrations are never edited, so every `state.db` still holds the two tables, and jrs-upgrade neither reads nor writes them. A home shared with jrsctl keeps working for jrsctl, which owns those rows. `StateStore` no longer has hotfix methods, and the classes `Hotfixes`, `HotfixInstalled`, `HotfixFile` and `HotfixState` are gone.
- **Retention protects every run another tool journaled.** `runs prune` never removes the snapshots or run directory of a run whose operation is not one of jrs-upgrade's own. Those operations are `export`, `import`, `upgrade`, `upgrade.rollback`, `upgrade.test` and `smoke --mutating`. jrsctl's hotfix runs in a shared home keep their rollback snapshots with no ledger read, whatever their outcome.
- **Hotfix facts come from the installed files.** #9's report keeps the jar labels under `WEB-INF/lib` and drops the recorded bundles. `record-upgrade` writes `upgrade.json` and the protected snapshot row and marks no hotfix; it has nothing left to compensate, since a rollback reads that record. `home set` and `home reset` refuse only for registered customizations and runs pending recovery.

**Consequences.**

- A run started by an earlier build and resumed by this one may leave a `record-upgrade.superseded` marker in its run directory. Nothing reads it, and the hotfix states it named stay as that run left them.
- jrsctl rows in a shared home are not reported anywhere by jrs-upgrade; jrsctl and jrs-hotfix are the tools to ask about them.
- Dropping the tables is left for a later migration, once no home shared with jrsctl needs them.
- This amends ADR-0001's line that "the state store keeps its hotfix tables and migrations": the tables and migrations stay, the code that used them does not.
