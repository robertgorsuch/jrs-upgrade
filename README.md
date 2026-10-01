# jrs-upgrade

**Plan, rehearse, run and roll back a JasperReports Server upgrade.** For JasperReports Server 7.x to 10.x on Apache Tomcat, on Windows and Linux.

jrs-upgrade wraps the vendor's own `js-upgrade-newdb` and `js-upgrade-samedb` procedures in a plan you can read before anything changes: a doctor pass, a full backup of the webapp, the configuration, the keystore and a repository export (rollback point B), the vendor upgrade with the keystore and master properties staged the way the upgrade guide requires, the vendor's post-upgrade tasks, a reconcile of the files the site customized, and a smoke test. A run that a crash or Ctrl-C interrupts is finished or undone from its journal. `upgrade --test` runs the vendor's own validation without touching the server.

This repository is the **extraction baseline** of jrsctl's upgrade subsystem (see `docs/decisions/0001-extraction-baseline.md`): everything jrsctl v2.3.0 did for an upgrade, minus the hotfix subsystem, under a new name. The twelve issues filed on 2026-10-01 describe where it goes next: multi-hop routing, an image mode for containerized servers, a guard for customer tables across a newdb hop, and the customization analysis an upgrade needs.

---

## What you can do with it

| You want to… | Command |
|---|---|
| Connect the tool to an installed server | `jrs-upgrade init --install-dir <dir>` |
| Check the server, the tools and the configuration | `jrs-upgrade doctor` |
| See what an upgrade would do, step by step | `jrs-upgrade upgrade --to 10.1.0 --package <unpacked bin zip> --plan` |
| Let the vendor scripts validate everything first | `jrs-upgrade upgrade --to 10.1.0 --package <dir> --test` |
| Run the upgrade | `jrs-upgrade upgrade --to 10.1.0 --package <dir> [--mode newdb\|samedb]` |
| Put the previous version back | `jrs-upgrade upgrade rollback <runId> --to-point B [--restore-database]` |
| Record the files the site changed so the upgrade keeps them | `jrs-upgrade customizations scan --vendor <vendor webapp> --register` |
| Export or import repository content on its own | `jrs-upgrade export --full-server --out <zip>` / `jrs-upgrade import <zip>` |
| Finish or undo an interrupted run | `jrs-upgrade runs recover <id> --resume` / `--rollback` |
| Prove the server still works | `jrs-upgrade smoke` |

Run `jrs-upgrade` with no arguments for a guided menu. Every command takes `--json`.

---

## Before you start

- **Run jrs-upgrade on the JasperReports Server machine**, as the account that installed the server (its `$HOME` holds the keystore pair `.jrsks` and `.jrsksp` the vendor scripts need). On Windows open Command Prompt with **Run as administrator**: buildomatic's batch wrappers need it.
- **Have the target distribution unpacked:** the WAR file distribution ZIP (`js-jrs_<version>_bin.zip`) from Jaspersoft Support, unpacked to a directory. `--package` points at it. A 10.x commercial target also needs `jaspersoft.jrs.license` in the home of the account running the upgrade.
- **Back up the repository database yourself for samedb.** A newdb upgrade is backed by the full export the tool takes after it stops the server; a samedb upgrade migrates the database in place and the tool asks you to confirm (`--db-backup-confirmed`) that you hold a database backup.
- **Supported path:** what the vendor's upgrade guides document, as recorded in `src/main/resources/compat/matrix.yaml`. A pair the matrix does not list is refused with exit 6 before anything changes. Routing a two-hop upgrade (for example 8.2 to 10.0 to 10.1) is issue #1; today each hop is its own run.
- **Not supported yet:** servers inside containers and servers on JBoss or WildFly (refused at `init` with exit 6; issue #2 is the container mode).

---

## Building

JDK 21 and the Maven wrapper. `scripts/mvn.sh` (or `scripts\mvn.cmd`) selects the JDK through `JRSUPGRADE_JDK`.

```
scripts/mvn.sh verify                     # compile, unit tests, format check, coverage floor
scripts/fast.sh test UpgradePlanTest      # compile and one test class
scripts/fast.sh fmt                       # google-java-format
```

The shaded jar is `target/jrs-upgrade.jar`; `java -jar target/jrs-upgrade.jar --help`.

---

## Documentation

`jrs-upgrade docs` prints the embedded operator guide, recovery runbook and security notes. They were inherited from jrsctl with the names replaced and still describe a few commands this tool no longer has; rewriting them is tracked as an issue. `docs/spec.md` carries the sections of jrsctl's specification that govern the kept code.

## Licence

GPL-3.0-only. See `LICENSE`.
