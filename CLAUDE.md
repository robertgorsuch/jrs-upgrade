# jrs-upgrade — agent guide

Upgrade tool for JasperReports Server, split out of jrsctl v2.3.0 (ADR-0001 in `docs/decisions/`). Spec: `docs/spec.md` (the jrsctl sections that govern this code; section numbers are jrsctl's). Backlog: the GitHub issues. Read the ADR before changing the shape of the code; it says what was cut and why.

## Build

- Always build through `scripts/mvn.sh` (Git Bash, Linux) or `scripts\mvn.cmd` (Windows): they select JDK 21 through `JRSUPGRADE_JDK`. The machine's default `java` may be older.
- Two tiers; iterate on the first, leave the second to CI.
  1. `scripts/fast.sh test <TestClass[,TestClass]>`: compile with Error Prone and `-Werror`, then only those classes. `scripts/fast.sh fmt` formats.
  2. `scripts/mvn.sh verify` = compile, every unit test, Spotless check, Jacoco floor (`jacoco.line.minimum` in the pom). Minutes, not seconds.
- The guard tests to run before a commit that adds a step, a command or a JSON field: `IdempotencyCoverageTest` (every `Step` has an idempotency test), `HelpExamplesTest` (every leaf command has examples), `JsonOutputSchemaTest` (every leaf command has a schema and a `--json` scenario), `ExplainTest` (every runnable command has a `### \`jrs-upgrade <path> ...\`` section in `docs/operator-guide.md`).
- Formatting is google-java-format through Spotless, checked in the `validate` phase, so a slip fails in seconds.
- Tests tagged `needs-jrs` or `needs-docker` are excluded by default. There is no acceptance suite yet (an issue); the shaded jar is `target/jrs-upgrade.jar`.

## Layout

One Maven module, package root `com.jaspersoft.jrsupgrade`, with jrsctl's module roots kept as sub-packages:

| Package | Owns |
|---|---|
| `core` | config + schema, secrets, `Platform`, snapshots, compat matrix, redaction, sealed `Event`s, engine (`Plan`, `Step`, `Runner`, retry, cancel, `EventBus`, `Journal`, run lock, `Recovery`), state store (SQLite, implements `Journal`) |
| `jrs` | REST v2 client and adapter, probes, export/import strategies (REST, vendor CLI), vendor-tool wrappers (buildomatic), keystore inspection, the service stop/start/wait steps every plan shares |
| `ops` | `upgrade`, `export`, `import`, `customizations` → `Plan`; `init`, `doctor`, `smoke` → report; `PlanRegistry`, `RunService`, `PlanJson`; `ops.hotfix` holds only the two home-path helpers the upgrade still uses |
| `app` | picocli commands, `--json`, progress renderer, guided menu, support bundle, `Main` |

`core.engine` must not import `core.state`: the engine names the journal it needs and the store implements it.

## Non-negotiables

- Mutations only inside a `Step`; every `Step.execute` is idempotent and has a compensation or is `irreversible()` with a justification comment.
- Read-only ops (`init`, `doctor`, `smoke`, `selfcheck`, `list`) are plain functions returning a report.
- Any change under `WEB-INF/lib` or `WEB-INF/classes` stops the service first, on both OSes.
- SQLite `state.db` is the only run journal (WAL, `synchronous=FULL`, append-only `step_transitions`).
- No `Runtime.exec(String)`, no shell; arguments as lists via `Platform.processes()`.
- All I/O through `Platform`; no `java.io.File`; streaming only.
- Secrets in `char[]`, never logged; every output stream passes the redaction filter.
- No new third-party dependency without an ADR in `docs/decisions/NNNN-title.md`.
- Ambiguity → safer option + ADR, then continue.

## Conventions

Java 21; records + sealed interfaces; pattern-matching `switch` with no `default` over sealed types; no `null` returns from public APIs. One-paragraph Javadoc stating invariants on every public class. Tests `should_<behaviour>_when_<condition>`. Conventional Commits, one logical change per commit.

## Exit codes

0 ok · 1 usage · 2 precheck/doctor/fingerprint, nothing mutated · 3 failed + rolled back · 4 failed, rollback incomplete · 5 cancelled · 6 unsupported · 7 signature · 8 recovery required · 9 lock held. Constants in `app/ExitCodes`.

## Branding

Product name `jrs-upgrade`, vendor line "Jaspersoft" in `--help` and `--version`; the guided menu names no company.
