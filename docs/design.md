# jrs-upgrade: the upgrade module split out of jrsctl

> **Historical record.** This is the design the extraction was reviewed against, written on
> 2026-10-01 in the jrsctl repository (branch `docs/jrs-upgrade-design`, never merged there) and
> moved here unchanged except that the author's home folder is shown as `~`. ADR-0001 records
> the extraction itself; later decisions are in `docs/decisions/` and the GitHub issues.

Status: draft for review before the first push. Written 2026-10-01, the day the
`robertgorsuch/jrs-upgrade` repository was created (empty) and seeded with issues #1 to #12
from the NGRA 8.2 to 10.1 upgrade guide.

## Problem

jrsctl v2.3.0 is the final release. The maintainer decided on 2026-09-28 to split it into
four applications: hotfixes (`jrs-hotfix`, shipped, v0.5.0), customizations, import/export,
and upgrades. This document covers the fourth: what of jrsctl becomes the first state of
`jrs-upgrade`, what is cut, and what is deferred to the seeded issues.

The jrs-hotfix split rebuilt the tool lean from the start (JSON ledger, no REST, seven-entry
menu). The upgrade subsystem cannot be rebuilt that way in one step: its plan reaches the
doctor, the full export, the engine, the SQLite journal, buildomatic and service control.
A dependency closure from `ops.upgrade` and `UpgradeCommand` touches 326 of jrsctl's 365
source files (47,090 of 57,107 lines). Rebuilding all of that before a first push would
take the same weeks it took to build it.

## Decision: extraction baseline first, slimming second

The first push of `jrs-upgrade` is an **extraction baseline**: jrsctl's upgrade subsystem
and everything it needs, copied into one Maven module under a new package, renamed, with
the hotfix subsystem cut out, building green with its unit tests. Slimming to the
jrs-hotfix shape (JSON ledger, no REST, trimmed menu) and the twelve seeded improvements
follow as issues on that baseline, each a reviewable change against working code.

This mirrors how jrs-hotfix itself moved from 0.1 to 0.4: ship what works, then remove.

## 1. Project shape

- Repository `robertgorsuch/jrs-upgrade`, public, fresh history, GPL-3.0-only (as jrs-hotfix).
- One Maven module: groupId `com.jaspersoft.jrsupgrade`, artifactId `jrs-upgrade`,
  version `0.1.0-SNAPSHOT`, Java 21, shaded jar `target/jrs-upgrade.jar`, main class
  `com.jaspersoft.jrsupgrade.app.Main`.
- Package root `com.jaspersoft.jrsupgrade` with the jrsctl module roots kept as
  sub-packages: `core`, `jrs`, `ops`, `app`. Keeping the roots keeps every Javadoc
  cross-reference, the `core.engine` to `core.state` rule and the step names valid; a
  flattening to `jrsupgrade.<area>` is a later refactor, not part of the extraction.
- Build stack copied from jrs-hotfix: Error Prone with `-Werror`, Spotless
  (google-java-format), Jacoco with a line floor, surefire for unit tests, failsafe
  reserved for acceptance (none in the baseline), the Maven wrapper, `scripts/mvn.{sh,cmd}`
  selecting JDK 21 through `JRSUPGRADE_JDK`, `scripts/fast.{sh,cmd}` for the two fast tiers.
- CI: one workflow, ubuntu and windows, Spotless check first then `verify`. No release
  workflow in the baseline (jrs-hotfix's `release.yml` is the template; separate issue).
- Dependencies: those the kept code imports. Runtime: picocli, Jackson (databind, yaml,
  jsr310, jdk8), SLF4J + Logback, sqlite-jdbc, json-schema-validator, commons-compress,
  commons-lang3, semver4j, JLine. Test: JUnit 5, AssertJ, WireMock, Mockito, jqwik. Each
  one re-justified or removed in the slimming issues; none added.

## 2. What is copied, what is cut

Copied (with the package rename): all of `core`, all of `jrs`, all of `ops` except the
hotfix package, and the `app` commands that an upgrade needs end to end: `init`, `doctor`,
`smoke`, `upgrade` (with `rollback` and `--test`), `customizations`, `runs`, `config`,
`secrets`, `selfcheck`, `docs`, `home`, the guided menu, `--json` output and its schemas.

Cut:

- `ops.hotfix` (31 files, 5,275 lines) except `HotfixPaths` and `HotfixException`, which
  are home-directory helpers the upgrade and customization code share (renaming them is a
  follow-up).
- Hotfix re-application during an upgrade: `HotfixReconciler`, `RepackHotfixBundle`,
  `ReconcileSteps.PlanHotfixReapply`, the `--reapply-hotfixes` flag, the hotfix factory in
  `UpgradeRuntime` and `PlanRegistry`. Reason: the hotfix ledger now belongs to jrs-hotfix;
  jrsctl's `hotfix_installed` table is never populated in this tool. Reading jrs-hotfix's
  `ledger.json` during reconcile is a new issue, not a port of the old step.
- `app`: `HotfixCommand`, `HotfixOps`, `KeysCommand`. `ExportCommand` and `ImportCommand`
  stay (revised during the extraction): the upgrade plan takes the full export and the
  rollback re-imports it, and the tests of the `runs` commands seed their runs through the
  export fake, so keeping the two thin commands cost nothing and saved four test rewrites.
- Resources: the hotfix manifest schema, the hotfix and keys JSON output schemas. The
  publisher signing key and `core.keys` stay for now because `RunService` references them;
  they go with the first slimming change.
- Modules `dist` (jlink image, signing) and `acceptance` (phase tests against the shaded
  jar). Both are issues: the acceptance harness is worth bringing over once the command
  surface is final.
- The hotfix authoring guide. The operator guide, recovery runbook and security notes are
  copied and renamed; rewriting them for the smaller tool is a follow-up.

## 3. Renaming rules

Applied mechanically to every copied file, in this order:

1. `com.jaspersoft.jrsctl` to `com.jaspersoft.jrsupgrade` (packages and directories).
2. `JRSCTL_` to `JRS_UPGRADE_` (environment variables such as the home override).
3. `Jrsctl` to `JrsUpgrade` (class and identifier prefixes).
4. `jrsctl` followed by an identifier character to `jrsupgrade` (identifiers such as
   `jrsctlHome`); any other `jrsctl` to `jrs-upgrade` (the command name in messages,
   examples, file names such as the YAML config, and system properties).
5. `jrsctl-version.properties` to `jrs-upgrade-version.properties`.

Anything the compiler or a test rejects after these rules is fixed by hand and listed in
the extraction ADR.

## 4. Hotfix surgery

The cut above leaves eight main classes referring to removed ones. Each change is the
smallest that compiles and keeps the upgrade plan's documented step order (spec §10.2 of
jrsctl) minus step 12:

- `DefaultUpgradeOperations`: drop the hotfix factory parameter, the re-apply planning
  block, the `reapplyHotfixes` input and warning; the service-stop rule for
  `WEB-INF/lib|classes` customizations stays (it was borrowed from the hotfix rule and is
  needed on its own).
- `UpgradeRuntime`: drop `hotfixOperations`.
- `ReconcileSteps`: drop `PlanHotfixReapply`; keep `PlanCustomizationReapply`. `EmbeddedStep`
  and `BundleZips` go with it (only the re-apply used them).
- `PlanRegistry`: drop the `hotfix.apply` and `hotfix.rollback` entries and the
  `reapplyHotfixes` argument.
- `UpgradeCommand`, `PlanExecutor`, `JrsUpgradeCommand`, `GuidedMode`, `HelpExamples`,
  `JsonSchemas`, `EmbeddedDocs`: remove the hotfix, export, import and keys entries.
- `UpgradeOptions` (wherever the record lives) loses `reapplyHotfixes`.

Tests that exercised the removed behaviour are dropped with it; tests of kept classes that
assert on hotfix wording are edited, not deleted.

## 5. Documentation in the first push

- `README.md`: what the tool is, that it is an extraction baseline of jrsctl's upgrade
  subsystem, the quick start as it works today, a pointer to the twelve issues.
- `CLAUDE.md`: the agent guide, adapted from jrsctl's (build tiers, non-negotiables, the
  engine-to-state rule, exit codes).
- `docs/spec.md`: jrsctl's spec sections that govern the kept code (compatibility matrix,
  export/import, upgrade subsystem, exit codes), renamed, with a header saying what it is.
- `docs/decisions/0001-extraction-baseline.md`: this decision, the cut list, and every
  hand fix the renaming rules needed.
- `docs/operator-guide.md`, `docs/recovery-runbook.md`, `docs/security.md`: copied and
  renamed, marked as inherited.

## 6. Acceptance for the first push

Outcome on 2026-10-01: `verify` green on Windows with 1,534 unit tests (10 skipped as
`needs-jrs`/`needs-docker`), line coverage 0.84, floor set to 0.80; the shaded jar lists
twelve commands and `upgrade --help` carries no `--reapply-hotfixes`. Three commits on
`main` of `~\jrs-upgrade`, not pushed.

- `scripts/mvn.sh verify` green on Windows: compile with Error Prone, every kept unit
  test, Spotless, the Jacoco floor set just under the measured coverage.
- The shaded jar answers `jrs-upgrade --version` and `jrs-upgrade --help` with no hotfix,
  export, import or keys command listed.
- `jrs-upgrade upgrade --help` shows no `--reapply-hotfixes`.
- Commits on `main`, Conventional Commits, not pushed until the maintainer has read this
  document and the ADR.

## Out of scope for the baseline (tracked as issues)

Multi-hop routing (#1), image mode (#2), the custom-table guard (#3), the customization
intelligence (#4 to #8), patched WAR input (#9), the template check (#10), the JNDI check
on 10.x (#11), the playbook renderer (#12); plus the slimming items this document creates:
flatten the package layout, replace the SQLite journal, decide on REST, trim the menu, port
the acceptance harness, add the release workflow, rewrite the inherited docs.
