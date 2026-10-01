# jrs-upgrade

**Plan, rehearse, run and roll back a JasperReports Server upgrade.** For JasperReports Server 7.x to 10.x on Apache Tomcat, on Windows and Linux.

jrs-upgrade wraps the vendor's own `js-upgrade-newdb` and `js-upgrade-samedb` procedures in a plan you can read before anything changes:
- a doctor pass;
- a full backup of the webapp, the configuration, the keystore and a repository export (rollback point B);
- the vendor upgrade, with the keystore and master properties staged the way the upgrade guide requires;
- the vendor's post-upgrade tasks;
- a reconcile of the files the site customized;
- a smoke test.

A run that a crash or Ctrl-C interrupts is finished or undone from its journal. `upgrade --test` runs the vendor's own validation without touching the server.

Beyond a single vendor upgrade:

- **Multi-hop routes.** When the vendor documents a pair only in hops (8.2.0 to 10.1.0 goes through 10.0.0), the plan follows the route. It runs every intermediate hop as a *transit* hop that deploys nothing, with one rollback point (ADR-0002).
- **Patched WARs.** `--war` deploys the vendor's pre-patched build instead of the package's own webapp. The plan also names hotfix-labelled jars and the version that actually runs.
- **Customer tables.** Tables and sequences in the repository database that a newdb upgrade would drop are named, their structure is saved, and `--custom-ddl` re-creates them before the server starts.
- **Customization analysis.** `customizations scan --target <package>` says what becomes of each change the site made. It works from rules kept as data in the compatibility matrix (ADR-0003), and the upgrade plan applies the same rules to the registered customizations:
  - a verdict for every added or patched jar;
  - the vendor classes custom code builds on, and the jars that need a Jakarta recompile;
  - where relocated settings went, with three-way merge files;
  - override constructs the target no longer accepts.
- **Ad Hoc templates.** After a newdb upgrade from before 9.0, the plan names the vendor templates the old export overwrote, and `--restore-vendor-templates` puts them back.

jrs-upgrade was split out of jrsctl v2.3.0 (ADR-0001) and does not apply hotfixes; that is jrs-hotfix's job. It keeps no hotfix ledger (ADR-0004).

---

## What you can do with it

| You want to… | Command |
|---|---|
| Connect the tool to an installed server | `jrs-upgrade init --install-dir <dir>` |
| Check the server, the tools and the configuration | `jrs-upgrade doctor` |
| See what an upgrade would do, step by step | `jrs-upgrade upgrade --to 10.1.0 --package <unpacked bin zip> --plan` |
| Upgrade across releases the vendor documents in hops | `jrs-upgrade upgrade --to 10.1.0 --package <10.0.0 dir> --package <10.1.0 dir>` |
| Let the vendor scripts validate everything first | `jrs-upgrade upgrade --to 10.1.0 --package <dir> --test` |
| Run the upgrade | `jrs-upgrade upgrade --to 10.1.0 --package <dir> [--mode newdb\|samedb] [--war <file>] [--custom-ddl <dir>]` |
| Put the previous version back | `jrs-upgrade upgrade rollback <runId> --to-point B [--restore-database]` |
| Record the files the site changed so the upgrade keeps them | `jrs-upgrade customizations scan --vendor <running version's distribution> --register` |
| See what becomes of those changes on the target | `jrs-upgrade customizations scan --vendor <running> --target <target> [--merge-dir <dir>]` |
| Export or import repository content on its own | `jrs-upgrade export --full-server --out <zip>` / `jrs-upgrade import <zip>` |
| Finish or undo an interrupted run | `jrs-upgrade runs recover <id> --resume` / `--rollback` |
| Prove the server still works | `jrs-upgrade smoke` |

Run `jrs-upgrade` with no arguments for a guided menu. Every command takes `--json`, and `--explain` prints its section of the operator guide without running anything.

---

## Before you start

- **Run jrs-upgrade on the JasperReports Server machine** with a Java 21 runtime: `java -jar jrs-upgrade.jar <command>`. Use the account that installed the server, whose `$HOME` holds the keystore pair `.jrsks` and `.jrsksp` the vendor scripts need. On Windows open Command Prompt with **Run as administrator**: buildomatic's batch wrappers need it.
- **Have the target distribution unpacked:** the WAR file distribution ZIP (`js-jrs_<version>_bin.zip`) from Jaspersoft Support, unpacked to a directory, one per hop for a route. `--package` points at it. A 10.x commercial target also needs `jaspersoft.jrs.license` in the home of the account running the upgrade.
- **Back up the repository database yourself for samedb.** A newdb upgrade is backed by the full export the tool takes after it stops the server, and `upgrade rollback --restore-database` rebuilds the old database from it. A samedb upgrade migrates the database in place, so the tool asks you to confirm (`--db-backup-confirmed`) that you hold a database backup.
- **Supported paths** are what the vendor's upgrade guides document, as recorded in `src/main/resources/compat/matrix.yaml`, together with the routes through its releases. A pair that neither a path nor a route reaches is refused with exit 6 before anything changes.
- **Not supported yet:** servers inside containers and servers on JBoss or WildFly. Both are refused at `init` with exit 6; issue #2 is the container mode.

---

## Building

JDK 21 and the Maven wrapper. `scripts/mvn.sh` (or `scripts\mvn.cmd`) selects the JDK through `JRSUPGRADE_JDK`.

```
scripts/mvn.sh verify                     # compile, unit tests, format check, coverage floor
scripts/fast.sh test UpgradePlanTest      # compile and one test class
scripts/fast.sh fmt                       # google-java-format
```

The shaded jar is `target/jrs-upgrade.jar`; `java -jar target/jrs-upgrade.jar --help`. There is no packaged runtime or installer yet: copy the jar to the server.

---

## Documentation

- `jrs-upgrade docs` prints the embedded documents, which are the files in this repository:
  - the operator guide (`docs/operator-guide.md`), with every command, flag, exit code and error message;
  - the recovery runbook (`docs/recovery-runbook.md`), what to do after every non-zero exit code;
  - the security notes (`docs/security.md`);
  - this README.
- `docs/decisions/` holds this repository's decisions:
  - ADR-0001, the extraction from jrsctl;
  - ADR-0002, multi-hop routes and transit hops;
  - ADR-0003, customization findings from matrix rules;
  - ADR-0004, the retired hotfix ledger.

  Higher ADR numbers cited in the docs and code are jrsctl's.
- `docs/spec.md` carries the sections of jrsctl's specification that govern the kept code, with a note on what was added since.
- The backlog is the GitHub issues.

## Licence

GPL-3.0-only. See `LICENSE`.
