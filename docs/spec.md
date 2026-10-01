# jrs-upgrade specification (carried from jrsctl)

> This is the part of jrsctl's specification (Draft 1.2) that governs the code extracted into
> jrs-upgrade on 2026-10-01 (ADR-0001). Names are replaced; section numbers are jrsctl's, so
> cross-references in Javadoc and tests still resolve. Sections about the hotfix subsystem, the
> removed web console, the build phases and the v1.0 definition of done are left out. Where the
> text says "module" read "package": jrs-upgrade is one Maven module with the four roots `core`,
> `jrs`, `ops` and `app` as sub-packages. The hotfix re-apply step (§10.2 step 12) and the
> `--reapply-hotfixes` flag no longer exist. ADR-0004 retires the hotfix ledger: the
> `hotfixes_installed` and `hotfix_files` tables stay in the schema but are neither read nor
> written, `RecordUpgrade` marks no hotfix, and retention protects every run another tool
> journaled instead of the runs of installed hotfixes. A slimmed specification replaces this file
> when the baseline has been reshaped (see the issues in the repository).

## 2. Definitions

| Term | Meaning |
|---|---|
| JRS | JasperReports Server (Community or Commercial/Pro) |
| Edition | `CE` or `PRO` |
| Tenancy | `SINGLE` or `MULTI` (organizations enabled) |
| Adapter | The `JrsAdapter` implementation that talks to the server over REST; behaviour is capability-driven |
| Strategy | An `ExportImportStrategy` (`REST` or `VENDOR_CLI`) chosen per operation |
| Plan | Ordered list of Steps, grouped into Phases, computed before execution |
| Phase | Named group of consecutive Steps; every Phase boundary is a rollback point |
| Step | Smallest unit of work with precheck, idempotent action, postcheck, compensation |
| Run | One execution of a Plan, with a unique `runId` |
| Snapshot | Hashed backup of files or repository content taken before a mutating Step |
| State store | SQLite database holding installed hotfixes, runs, step transitions, snapshots, plans, audit |
| Journal | The append-only `step_transitions` table in the state store, used for crash recovery |
| Fingerprint | Hash of every input a Plan depends on; execution refuses a Plan whose fingerprint no longer matches |
| Run lock | File lock in `$JRS_UPGRADE_HOME` held for the duration of any mutating run |
| Bundle | Signed ZIP containing a hotfix manifest and payload |
| Isolated mode | Outbound HTTP permitted only to the `server.baseUrl` host; everything else refused and audited |
| Public mode | Outbound network permitted; proxies and TLS trust configurable |

---

## 3. Architecture overview

```
┌──────────────────────────────────────────────────────────────────┐
│  app: Main entry + wiring                                        │
│              ┌──────────────┐                                    │
│              │  cli (Picocli)│                                   │
│              └──────┬───────┘                                    │
│                     ▼                                             │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │  ops: hotfix, export, import, upgrade, init, doctor,     │   │
│  │       smoke, customizations  → produce Plans or Reports  │   │
│  └───────────┬──────────────────────────────┬───────────────┘   │
│              ▼                              ▼                    │
│  ┌───────────────────────┐    ┌──────────────────────────────┐  │
│  │  core: config, secrets │    │  jrs: REST client, JrsAdapter │  │
│  │  platform, state store │◀───│  capabilities, strategies,    │  │
│  │  snapshots, engine     │    │  vendor-tool wrappers         │  │
│  │  (Plan/Step/Runner),   │    └──────────────────────────────┘  │
│  │  events, redaction     │                                       │
│  └────────────────────────┘                                       │
└──────────────────────────────────────────────────────────────────┘
```

Principles:

- **Plan then apply.** Nothing mutates until a Plan is shown and confirmed.
- **One engine, one front end.** The CLI (direct commands and guided mode) consumes the event stream live; `runs show` and `runs support-bundle` replay it from the journal.
- **Capabilities isolate version drift.** Ops code never branches on JRS version; it asks the adapter for capabilities.
- **Orchestrate vendor tools.** Buildomatic and js-export/js-import are wrapped, not rewritten.
- **Fail closed.** Any uncertainty halts before mutation with a clear next action.
- **Idempotent steps.** Any Step can be re-executed after a crash and converges to the same end state.

---

## 4. Module map

Maven multi-module project. Package root: `com.jaspersoft.jrsupgrade`.

| Module | Responsibility | Depends on |
|---|---|---|
| `core` | Config, secrets, platform abstraction, state store (incl. journal), snapshots, compat matrix, redaction, event model, engine (`Plan`, `Step`, `Runner`, retry/cancel, `EventBus`), run lock | — |
| `jrs` | REST v2 client, `JrsAdapter`, capability probes, `ExportImportStrategy` implementations, vendor-tool process wrappers, keystore inspection, the service stop/start/wait steps every plan shares (ADR-0015) | `core` |
| `ops` | Operation implementations producing Plans (mutating) or Reports (read-only) | `core`, `jrs` |
| `app` | Picocli commands, `--json` output, progress tree renderer, guided mode, support bundle, main entry, shaded JAR | `ops` |
| `dist` | jlink runtime image, portable ZIP/tar.gz, SBOM, checksum and signing steps (signing executes in CI only) | `app` |
| `acceptance` | Phase acceptance scripts and Testcontainers harness | all |

---

## 5. Core module

### 5.1 Configuration

- Single config directory: `$JRS_UPGRADE_HOME` (default: `%ProgramData%\jrs-upgrade` on Windows, `/var/lib/jrs-upgrade` on Linux). It falls back to `~/.jrs-upgrade` when the system home does not exist and cannot be created; a system home that exists but is not writable by this user is refused with the reason, since two operators would otherwise change one installation from two journals (#50). A home that holds a `home.redirect` file naming an absolute directory is replaced by that directory, one hop only, whichever of `--home`, `JRS_UPGRADE_HOME` or the default chose it; `jrs-upgrade home set <dir>` writes the file and `jrs-upgrade home reset` removes it (ADR-0041).
- Files: `config.yaml`, `state.db` (§5.4), `runs.lock` (§5.5), `snapshots/`, `runs/` (per-run temp and staging), `keys/`, `secrets.enc` (§5.2).
- Every config key overridable by env var `JRS_UPGRADE_<UPPER_SNAKE_KEY>` and by CLI flag. Precedence: flag > env > file > default.
- A leading `~` (alone, or `~/` and `~\`) in any path means the operator's home directory (`HOME`, else `USERPROFILE`, else the JVM's `user.home`) everywhere jrs-upgrade reads one: `--home` and `JRS_UPGRADE_HOME`, every `Path` option, every path-valued key from the file, the environment or `--set`, and the guided menu's answers; `~user` and an inner `~` are left as typed (`UserPaths`, field test 2, G3). `config set` and `init`'s review refuse a directory or file setting whose target does not exist (exit 1, `no such directory: <expanded path>`) and store the expanded path, since the service account that reads the file later has another home; `--set` at load time stays syntax-only and `doctor` reports a tree that vanished after it was written (field test 2, G9).
- `jrs-upgrade init` (§12.0) detects the installation and writes `config.yaml`; `doctor` validates it.
- `config.yaml` schema (JSON Schema in `core/src/main/resources/schema/config.schema.json`):

```yaml
server:
  baseUrl: http://localhost:8080/jasperserver-pro
  webappName: jasperserver-pro          # jasperserver | jasperserver-pro; explicit, not derived
  installDir: /opt/jasperreports-server
  tomcatDir: /opt/jasperreports-server/apache-tomcat
  buildomaticDir: /mnt/jrs-dist/buildomatic # optional; see 7.4 (another volume, a mount or a share)
  runAsUser: jasperserver               # OS account that runs Tomcat; owns ~/.jrsks and ~/.jrsksp
  auth:
    mode: basic                         # basic (default) | form | token
    username: jasperadmin
    passwordRef: env:JRS_PASSWORD       # env:NAME | file:/path | enc:NAME  (see 5.2)
    tokenLocation: query                # query (default) | header; token mode only (ADR-0018)
service:
  kind: systemd                         # windows-service | systemd | ctlscript | catalina | manual
  name: jasperreportsTomcat             # windows-service / systemd only
  scriptPath: /opt/jasperreports-server/ctlscript.sh   # ctlscript / catalina only
  stopTimeoutSeconds: 180
  # forceStopAfterSeconds: 60           # ctlscript / catalina only, off when absent, below stopTimeoutSeconds:
                                        # ends this Tomcat's JVM if it outlives the stop script (ADR-0016)
database:                               # required only for hotfixes that carry SQL
  type: postgresql                      # postgresql | mysql | oracle | mssql | db2
  url: jdbc:postgresql://localhost:5432/jasperserver
  username: jasperdb
  passwordRef: env:JRS_DB_PASSWORD
  driverDir: null                       # default: <buildomatic>/conf_source/db/<type>/jdbc (7.4)
vendor:
  javaHome: /opt/jasperreports-server/java   # JDK used to run buildomatic; never jrs-upgrade's bundled runtime
network:
  mode: isolated                        # isolated | public
  proxy: { host, port, username, passwordRef, noProxy: [host | .suffix] }
  trustStore: { path, passwordRef }
backups:
  retentionDays: 30
  maxSnapshots: 20
smoke:
  reportUri: /public/Samples/Reports/AllAccounts   # WARN if absent
```

A `console:` block from a 1.x file (or a `console.*` line in `jrs-upgrade.properties`) was ignored with a warning in 2.0 and is refused from 2.1 on every read, naming ADR-0038 and `jrs-upgrade config unset console`, the one command that reads past it to remove it (#154).

Rules:
- `network.mode: isolated` is enforced in the HTTP client: an allowlist containing only the `server.baseUrl` host. Any request to another host is refused, logged as `FAIL`, and audited. This makes isolated mode testable with WireMock.
- `vendor.javaHome` is mandatory for upgrade and vendor-strategy export/import. `doctor` verifies it is one of the Java majors the compat matrix lists for the detected JRS version (7.x: 8; 8.x: 8 or 11; 9.x: 8, 11 or 17; 10.0: 17; 10.1: 17 or 21, from the vendor platform-support sheets). jrs-upgrade's own runtime is never passed to buildomatic.
- `service.kind: manual` means jrs-upgrade prints the stop/start instruction, waits for the operator (or `--yes` fails with exit code 2), and polls `serverInfo` until the server state changes.
- A WAR + buildomatic install usually has no vendor-registered service; its Tomcat is started with `bin/startup` or by a service the customer's team named. `catalina` is the kind for a Tomcat started by script (jrs-upgrade runs `catalina stop|start` and judges state from the process list, so a Tomcat started with `startup` is seen), `manual` the kind for one a supervisor or other tooling owns; there is no separate `script` kind (ADR-0042, issue #147).

### 5.2 Secrets

- `SecretRef` resolves three sources:
  - `env:NAME` — environment variable.
  - `file:/path` — file contents; `doctor` FAILs if the file is group/world readable (Linux) or has ACLs beyond the owner and Administrators (Windows).
  - `enc:NAME` — entry in `$JRS_UPGRADE_HOME/secrets.enc`, AES-GCM (JDK built-in) with a key derived (PBKDF2-HMAC-SHA256, 600k iterations) from a machine-bound salt plus an operator passphrase. Managed by `jrs-upgrade secrets init|set|remove|list`.
- Non-interactive unlock of `secrets.enc`: `JRS_UPGRADE_PASSPHRASE` env var or `--passphrase-file <path>`. If neither is present and stdin is not a TTY, resolution fails with exit code 2 and a clear message.
- Secrets are held in `char[]`, zeroed after use, never `toString()`-able.
- OS keyrings are out of scope for v1 (§1.3).

### 5.3 Platform abstraction

Interface `Platform` with `WindowsPlatform` and `LinuxPlatform`:

```java
interface Platform {
  OsFamily os();
  Arch arch();
  ServiceController services(ServiceConfig cfg);   // status/start/stop for the configured service.kind
  FileOps files();                                  // atomic replace, lock detection, perms, ACL preserve, streaming hash
  ProcessRunner processes();                        // ProcessBuilder wrapper, no shell, streamed output, timeout
  Path defaultHome();
  TomcatLayout detectTomcat(Path installDir);
  List<Path> candidateInstallDirs();                // used by `init`
}
```

Rules:
- No `Runtime.exec(String)`. No shell invocation. Arguments passed as lists.
- **Any change under `WEB-INF/lib` or `WEB-INF/classes` requires the service to be stopped, on both OSes.** There is no replace-on-restart strategy. A hotfix manifest may declare `restart: none` only when every file it touches is outside those directories and is not held open by Tomcat; `Preflight` verifies this and fails the Plan otherwise.
- Windows: detect locked files before writing even after a service stop (lingering processes). If still locked after `service.stopTimeoutSeconds`, fail the Plan with the holding process id where available.
- Preserve file ownership/permissions/ACLs on replace.
- `ServiceController` polls service state after stop/start; it never assumes the operation completed synchronously. Windows uses `sc.exe query|stop|start`; Linux uses `systemctl` or the configured script.
- All file I/O is streaming. No whole-file `byte[]`. SHA-256 is computed while streaming; downloads and archives are written incrementally.

### 5.4 State store

- SQLite file `state.db` via `sqlite-jdbc`. Opened with `journal_mode=WAL`, `synchronous=FULL`, `foreign_keys=ON`.
- **The state store is the single source of truth for run state.** There is no separate on-disk journal.
- Tables:
  - `servers` — detected identity, last seen.
  - `hotfixes_installed` — `id` (manifest id, the primary key), version, installed run, snapshot ref, state (`INSTALLED`, `ROLLED_BACK`, `SUPERSEDED`), origin (`JRSCTL`, the row owns its files and a snapshot; `RECORDED`, applied by hand and entered with `hotfix record`, nothing to roll back; ADR-0030, V003).
  - `hotfix_files` — every path a hotfix added/replaced/deleted, with before/after hashes; used for overlap and LIFO rollback checks (§8.4).
  - jrs-upgrade keeps both hotfix tables for homes jrsctl wrote but never reads or writes them (ADR-0004).
  - `customizations` — registered paths with original hash at registration and snapshot ref (§10.3).
  - `plans` — serialized Plan JSON, fingerprint, created at, expires at (TTL 30 minutes), consumed by run id.
  - `runs` — id, op, plan id, started, ended, terminal state, exit code.
  - `step_transitions` — the journal: `{ts, runId, stepId, phase, from, to, detail}`; append-only (trigger prevents UPDATE/DELETE).
  - `snapshots` — id, run, step, path, manifest hash, referenced-by.
  - `audit` — append-only (trigger prevents UPDATE/DELETE).
- All writes in transactions. Schema versioned with migrations in `core/src/main/resources/db/migrations/`.
- Support bundles export `step_transitions` for a run as JSONL; that export is derived, never read back.

### 5.5 Run lock and recovery

- `runs.lock` in `$JRS_UPGRADE_HOME` is taken (OS file lock) before any mutating Plan executes and held until the run reaches a terminal state. A second mutating run fails immediately with exit code 9 and the holder's run id and pid. The lock is checked before the pending-run check, and by trying it rather than by reading the pid out of the file: a run another process is executing is 9 ("wait for it"), and only a run no process holds any more is 8 ("recover it"). A process that holds the lock never opens a second handle on the file: on Linux the lock is a POSIX record lock, which the kernel drops when any descriptor of the process on that file is closed, so the locks a process holds are kept in an in-process registry and `heldBy`/`readHolder` answer from it before touching the file.
- On startup, `jrs-upgrade` queries `runs` for rows without a terminal state. In interactive mode it offers `resume` or `rollback` (§6.6). In non-interactive mode (`--yes`, `--non-interactive`, `--json`) any mutating command fails with exit code 8 and prints the exact `runs recover` command to run. `--non-interactive` never confirms anything: a plan that needs confirmation exits 2 unless `--yes` is also given; without either flag the confirmation is asked on the terminal, or on stdin when stdout is not a terminal, with end of input meaning no.

### 5.6 Snapshots

- `snapshots/<runId>/<stepId>/` containing payload and `manifest.json` with SHA-256 per file, source paths, permissions/ACLs, owner, and timestamp.
- Verified on creation and again before any restore.
- Retention pruning respects `backups.*` and never prunes a snapshot of a run another tool journaled in the home (jrs-upgrade's ledger-free replacement for "referenced by an installed hotfix", ADR-0004), referenced by a registered customization, or by the most recent successful upgrade.
- An upgrade's set (`snapshots/<runId>/`: the webapp and buildomatic archives, the full export, `upgrade.json`) follows its run and is pruned with it. Before an upgrade starts, `verify-target-package` refuses a run whose backups will not fit under the home (the trees to archive, an export estimate of the larger of 1 GB and the webapp tree, 512 MB headroom, plus the margin), and `full-export` re-checks its own need; the plan names the backup location, its free space and `--home`/`JRS_UPGRADE_HOME`; `init` prints the home and its free space; `doctor`'s `disk` item measures the home's volume as well as the installation's (field test 2, U3).
- The run directories `runs/<runId>/` (bundle copies, staging, markers) follow the same rules: one goes only when its run has ended, started before the retention cut-off, and neither it nor the run it is a `<runId>-hf-<slug>` sub-run of is protected; a leftover `hotfix-verify-*` working directory goes by age; no other name under `runs/` is touched. Removed run directories are listed with the removed snapshots; the kept and protected counts stay snapshot counts (#53).

### 5.7 Compatibility matrix

- `compat/matrix.yaml` bundled (unsigned; it ships inside the same artifact as any key that could verify it). Lists supported JRS versions, editions, the Java majors buildomatic may run on (`javaForBuildomatic`, a list: every JDK the vendor platform sheet lists for the release line), the certified Tomcat version ranges (`tomcat`), app servers, databases, supported upgrade paths with the modes the vendor offers for each pair (`modes: [samedb, newdb]`; a path without `modes` allows both), and per-version capability expectations. Matrix version 2 (review §1.2, §1.3, §2.1); 10.0 and 10.1 are separate entries because 10.1 adds JDK 21.
- `doctor` fails on unsupported combinations unless `--allow-unsupported` is passed (logged to audit; exit code unchanged).

### 5.8 Redaction

- `RedactingFilter` applied to all log appenders, event payloads, support bundles, and `--json` output.
- Patterns: configured secret values in raw, Base64, URL-encoded and JSON-string-escaped forms (the JSON outputs serialise first and redact afterwards, so a value holding `"` or `\` appears there escaped); a value made only of letters is masked in its raw form only where no letter, digit or underscore touches it, so a word secret does not mangle identifiers that contain it (`org.postgresql`) or spell itself out around the mask (#199); `password=`; `Authorization:`; JSESSIONID; bearer tokens; keystore passwords.
- Test: any string registered as a secret must not appear in any output stream in any of the three encodings (property-based test with jqwik).

### 5.9 Event model

Typed, sealed event hierarchy so `--json` output validates against a schema:

```java
sealed interface Event permits PlanCreated, StepPending, StepRunning, StepRetry, StepSucceeded, StepFailed,
                              StepSkipped, StepRolledBack, StepRollbackFailed, Log,
                              RunSucceeded, RunFailed, RunCancelled, RunRolledBack {
  Instant ts(); String runId(); Optional<String> stepId(); String phase();
}
record StepFailed(Instant ts, String runId, Optional<String> stepId, String phase, StepFailure failure) implements Event {}
record StepRollbackFailed(Instant ts, String runId, Optional<String> stepId, String phase, String cause, List<Path> backups) implements Event {}
// ... one record per event type; payload fields are typed, never Map<String,Object>
```

`StepRollbackFailed` is what drives exit code 4.

---

## 6. Engine (in `core`)

### 6.1 Step contract

```java
interface Step {
  String id();
  String title();
  String phase();                               // every Step belongs to a named Phase; boundaries are rollback points
  boolean irreversible();                       // must be false unless justified in a comment
  CheckResult precheck(Context ctx);            // sealed: Pass | Warn(msg) | Fail(msg, remediation)
  StepResult execute(Context ctx, EventSink out);   // MUST be idempotent: re-execution converges to the same end state
  CheckResult postcheck(Context ctx);           // Fail here is a StepFailure.Recoverable
  StepResult compensate(Context ctx, EventSink out);  // no-op only if irreversible()
  RetryPolicy retryPolicy();                    // NONE or bounded exponential backoff
}

sealed interface StepResult permits StepResult.Ok, StepResult.Failed {}
record Failed(StepFailure failure) implements StepResult {}

sealed interface StepFailure permits Retryable, Recoverable, Fatal {
  String cause(); List<Path> affectedPaths(); List<URI> affectedUris(); List<Path> backups(); String nextAction();
}
```

Rules:
- Steps classify their own failures; the Runner never infers class from exception type.
- Idempotency is tested: the Phase 8 crash-injection suite kills the process mid-Step and re-executes the Step; end state must equal a clean run.
- `precheck` and `postcheck` both return `CheckResult`; there is no exception-based contract.

### 6.2 Plan and fingerprint

- `Plan` = ordered `List<Step>` grouped by `phase()` + `PlanSummary` (files touched, resources touched, service restarts, backup locations, rollback point per phase, chosen strategy, explicit warnings such as "database rollback is the operator's responsibility").
- Plans serialize to JSON for `--plan` output; stored in the `plans` table with a 30-minute TTL for `runs recover`.
- `PlanFingerprint` = SHA-256 over: server identity (`serverInfo` response), input artifact hash (bundle, archive, or upgrade package), SHA-256 of every target file the Plan will touch, and the resolved effective config. Execution recomputes the fingerprint and refuses to run (exit code 2) if it differs.

### 6.3 Runner

- Takes the run lock (§5.5), executes Steps sequentially, emits events to `EventBus`, writes every transition to `step_transitions` inside a transaction before emitting the event.
- On `StepFailed`:
  - `Retryable` → apply `RetryPolicy`, emit `StepRetry` per attempt; exhausted retries become `Recoverable`.
  - `Recoverable` → compensate the failing Step itself first when it is mutating and its `execute` ran (a precheck failure never ran and is not compensated), then run compensations in reverse order for all succeeded Steps back to the nearest Phase boundary (or the full plan with `--rollback-all`), emit `RunRolledBack` (exit 3). Any compensation failure emits `StepRollbackFailed` and ends the run with exit 4. (ADR-0009)
  - `Fatal` → halt, emit `RunFailed` with backup locations and manual next steps (exit 4 if state was mutated, else 2).
- Every failure message includes: step id, phase, cause, affected paths/URIs, backup location, next available action.

### 6.4 Cancellation

- Single cancellation token shared by Ctrl-C and timeouts.
- Cancellation completes or compensates the in-flight Step; it never abandons a partial write. Exit code 5.

### 6.5 Retry policy

- Default for HTTP steps: 5 attempts, base 2s, factor 2, max 60s, jitter 20%.
- Steps that hit the server during restart use a `waitForServer` sub-step with a 10-minute cap.

### 6.6 Resume and recovery

- `jrs-upgrade runs recover <runId> --resume|--rollback`.
- Every command that takes a run id accepts part of it (#186): an exact id wins, otherwise the text must be the end of exactly one id or its start (with or without `r-`); several matches exit 1 listing them, none is the unknown-run refusal. Run ids keep their format. `runs list` filters by `--status`, `--operation` (itself or a dotted prefix) and `--since` (date, instant or age) before `--limit`.
- `jrs-upgrade runs support-bundle <id> [--out <zip>]` — the full contract is §12.4.
- Resume re-runs the precheck of the interrupted Step and then re-executes it (idempotency guarantees convergence). If the precheck fails, only rollback is offered.
- Rollback compensates every succeeded Step of the run in reverse, after first compensating a mutating Step the journal left `RUNNING` or `FAILED` (the process died before that Step's own compensation ran). A compensation must therefore converge from any partial state, including one where `execute` never started.

---

## 7. `jrs` module

### 7.1 Server detection and capabilities

- `GET {baseUrl}/rest_v2/serverInfo` → version, edition, features (tenancy is derived from the `MT` feature flag).
- Capability probing after version detection: each capability (`EXPORT_ASYNC`, `IMPORT_ASYNC`, `KEYSTORE_ENCRYPTION`, `ORGS`, `TOKEN_AUTH`, `PREAUTH`, `REST_LOGIN`) is verified by a non-mutating probe request. The compat matrix records the *expected* capability set per version; `doctor` WARNs on a mismatch between expected and probed. `KEYSTORE_ENCRYPTION` is taken from the matrix for a listed version and probed through `GET /rest_v2/keys/` (200 or 204 is present; REST reference 10.1 p.128) for one the matrix does not list. `CLUSTERING` is the licence's, not the release line's: `GET /rest_v2/licenseFeatures` answering `cl: true` (pp.20-22; 404 on the community edition means absent); the matrix never expects it, `doctor` compares nothing against it, and a plan that changes `WEB-INF` (hotfix apply, upgrade) carries a warning that the change reaches this node only (issue #112, review §3.1–3.2).

### 7.2 JrsAdapter

One implementation, `RestJrsAdapter`, whose behaviour is driven entirely by probed capabilities. Version-specific quirk classes are added only when the nightly contract suite (§16) demonstrates drift, each with an ADR and a contract test.

```java
interface JrsAdapter {
  ServerIdentity identity();
  Session login(Credentials c);                   // basic by default; REST_LOGIN or form when required
  ExportHandle startExport(ExportRequest r);
  ExportStatus pollExport(ExportHandle h);
  Path downloadExport(ExportHandle h, Path target); // streamed to disk
  ImportHandle startImport(ImportRequest r, Path archive);
  ImportStatus pollImport(ImportHandle h);
  KeystoreInfo keystore();                         // resolved from server.runAsUser's home
  Set<Capability> capabilities();
  HealthReport health();                           // used by doctor and smoke
}
```

### 7.3 Export/import strategies

```java
sealed interface ExportImportStrategy permits RestStrategy, VendorCliStrategy {
  List<Step> exportSteps(ExportRequest r);
  List<Step> importSteps(ImportRequest r);
  boolean requiresServiceStop();                   // VendorCli: true; Rest: false
}
```

`VendorCliStrategy` wraps `js-export`/`js-import`. It is not a `JrsAdapter`; it cannot log in or report health. Selection rules are in §9.2.

### 7.4 Vendor tool wrappers

- Locate the installed `buildomatic/` (ADR-0013): `server.buildomaticDir` when set, authoritative even when it cannot be reached (nothing is substituted for it); else `<installDir>/buildomatic`; else a neighbour that holds this platform's `js-ant` and a `default_master.properties`, beside `server.tomcatDir` or `installDir` or inside a `jasperreports-server*` directory under or beside `installDir`, two such neighbours being refused as ambiguous. The upgrade target package's tree is always `<package>/buildomatic`. Verify expected scripts exist for the detected version.
- Invoke with `ProcessRunner` using `vendor.javaHome` as `JAVA_HOME` and its `bin` first on `PATH` (the wrappers start the export/import command with the first `java` on `PATH` unless a `java` folder sits next to buildomatic), stream stdout/stderr into events (redacted), capture exit code, enforce timeout.
- Judge a run by the tool's own output as well as its exit code, which the wrappers do not reliably propagate. The line `A new encryption key and a new keystore are about to be created` (buildomatic `setup.xml`, printed before `create-ks`) makes any run a failure whatever it exited with: the repository's passwords are now encrypted with a key no other copy of the server has (review §1.4). Ant's `BUILD FAILED`, or the Windows wrappers' `Checking Ant return code: BAD`, is a failure. `js-import` also needs Ant's `BUILD SUCCESSFUL` or `VALIDATION COMPLETED`, which belongs to the validation that runs before the import only. `js-export` and `js-import` succeed only when the export/import command printed `Done` after `Processing started` and logged no `ERROR BaseExportImportCommand`: the command can throw after a successful validation while the wrapper exits 0 (#40).
- Never modify vendor scripts. Property overrides are written to `default_master.properties` **in the buildomatic directory being invoked** (the upgrade target package's copy for upgrades; a run-scoped copy of the installed buildomatic for export/import). The pre-existing file, if any, is snapshotted first and restored by compensation. jrs-upgrade never adds a key whose name contains `pass` to that file, with one exception: `upgrade --key-alias <alias> --key-password-ref <ref>` writes `deprecatedImportExportEncSecret.keyalias` and, resolved at execute time and redacted, `deprecatedImportExportEncSecret.keypass`, the only route by which buildomatic's `import-export.xml` passes a key to the import an upgrade runs (ADR-0028). Open question Q5 (§19) tracks whether `js-ant` accepts an out-of-directory property file; if it does, prefer that.

### 7.5 REST client

- `java.net.http.HttpClient` with configurable proxy, trust store, timeouts, cookie management, and the isolated-mode host allowlist (§5.1).
- All responses parsed with Jackson `FAIL_ON_UNKNOWN_PROPERTIES=false`.
- Correlation id header per run for server-side log matching.

---

## 9. Export / import subsystem

### 9.1 Unified request model

```java
record ExportRequest(Scope scope, Set<String> uris, boolean includeUsersRoles, boolean includeAccessEvents,
                     boolean includeAuditEvents, boolean includeMonitoring, boolean includeSettings,
                     boolean fullServer, Path output, boolean stopService, Optional<String> keyAlias,
                     Optional<String> organization, boolean skipDependentResources, boolean skipFavoriteResources)
record ImportRequest(Path archive, boolean update, boolean skipUserUpdate, boolean includeAccessEvents,
                     boolean includeAuditEvents, boolean includeMonitoring, boolean includeSettings,
                     boolean skipThemes, Optional<Path> sourceKeystore, Optional<SecretRef> sourceKeystorePassword,
                     BrokenDependencies brokenDependencies, Optional<String> keyAlias,
                     Optional<String> organization, boolean mergeOrganization)
```

- `organization` limits an export to one organisation (its resources, users and roles, sub-organisations included; every URI relative to it) and names the organisation an import goes into; `mergeOrganization` merges the archive's organisation into the target when their ids differ, the archive's users, roles and resources overriding same-named ones (REST reference 10.1 pp.110, 115, 121; administrator guide 10.0 pp.263, 267). REST: export body `organization`, import query `organization` and `mergeOrganization`; vendor: `--organization` on both scripts and `--merge-organization` on `js-import`. The sidecar records the organisation an export was scoped to (field test 2, E5, I4).

- `keyAlias` names a key of the server's keystore to encrypt the export with, or to decrypt the import with, instead of the server's own import/export key (REST reference 10.1 pp.110, 117: the alias must exist in the importing server's keystore). It reaches the server as the REST export body field `keyAlias` and the import query parameter `keyAlias`, and the vendor tools as `--keyalias` on both scripts. `export --portable` is `--key-alias deprecatedImportExportEncSecret`, the alias every keystore since 7.5 holds, which makes the archive importable on any server that names the same alias (field test 2, E4, I1).

- `skipDependentResources` and `skipFavoriteResources` are export-only parameters (REST reference 10.1 p.111; no import equivalent): the first omits resources a selected resource depends on (data sources, queries, files included by reference), the second omits resources added to Favorites. Both default `false`. REST: export body `skip-dependent-resources` and `skip-favorite-resources`; vendor: `--skip-dependent-resources` and `--skip-favorite-resources` on `js-export`. An archive made with `skipDependentResources` will fail to import cleanly unless the same dependencies already exist at the destination; `export`'s help text says so (vendor doc review §4.2, issue #144).

### 9.2 Strategy selection

- `RestStrategy` when `EXPORT_ASYNC`/`IMPORT_ASYNC` probes pass and `fullServer=false`.
- `VendorCliStrategy` when `fullServer=true`, when probes fail, or when `--strategy vendor` is passed. The vendor strategy always includes `StopService` before `js-import`, and `StartService` after. It stops the service around `js-export` only with `--stop-service`; by default the export runs against the running server (ADR-0021, #67). The import's pre-import snapshot is such an export and never stops the service, so a vendor import has exactly one outage: stop, (keystore import), `js-import`, start, wait (ADR-0040). A plan rebuilt from stored arguments without `snapshotStopsService` (written before ADR-0040) keeps the stop and start around the snapshot, so `runs recover` matches the journaled run.
- When `--strategy vendor` forced the vendor tools on a server whose `IMPORT_ASYNC` probe passes, and neither `--source-keystore` nor the 2 GB REST limit needs them, the import plan warns that the REST route needs no outage at all (ADR-0040).
- Strategy and its service-stop consequence are shown in the Plan summary.

### 9.3 Keystore handling

- `KeystoreInfo` resolves `.jrsks`/`.jrsksp` in this order, and its reason names the source that settled it: the running server's own `WEB-INF/classes/keystore.init.properties` (`ks`, `ksp`; the file the server reads at startup, so the one a stale buildomatic copy or a moved installation cannot contradict), then `buildomatic/keystore.init.properties`, then the home directory of `server.runAsUser`, else the current user's (vendor review §1.5, issue #105). `doctor` FAILs when none of those yields a readable keystore on a server with `KEYSTORE_ENCRYPTION`, and WARNs when a found file is readable beyond its owner: the vendor asks for 600 or 640, so on Linux any "others" bit warns and on Windows an ACL beyond the owner, SYSTEM and Administrators does. `KeystoreInfo.exposure` carries that warning.
- Export records the keystore fingerprint in a sidecar `<archive>.jrs-upgrade.json`, and the key alias the export used, when one was given (`flags.keyAlias`).
- Import first compares the archive's SHA-256 with the one the sidecar recorded (`precheck.archive-checksum`, issue #185): a mismatch fails before the snapshot, the fingerprint check or any stop (exit 2), and the plan warns about it when it is built. The step decides in its precheck from the hash computed when the plan was built, which the plan fingerprint covers; it is a step, not a refusal while planning, so `runs recover` still rebuilds a run an earlier jrs-upgrade started. No sidecar, no step.
- An import that names a key alias, on the command line or through the sidecar (planning adopts the sidecar's alias when the operator gives none, and says so), skips the fingerprint comparison: the named key decrypts the archive, this server's own keystore is not what it was encrypted with. A portable export therefore moves between servers without `--source-keystore`.
- Import compares fingerprints; on mismatch, fail before mutation with instructions, or accept `--source-keystore` and `--source-keystore-password-ref` to perform the vendor-documented keystore import step first.
- Import compares versions: "Resources exported from version 10.1.0 cannot be imported into older versions" (release notes 10.1 p.6), so an archive whose sidecar records a source version of 10.1.0 or later is refused at plan time (exit 2, nothing snapshotted or imported) when this server is below 10.1.0, naming both versions and the remedies. `--force-version` attempts it anyway, with a warning in the plan and an audit row. Without a sidecar, or with a version that does not parse on either side, nothing is judged (issue #107).
- Import guards for size and themes (vendor review §4.3, issue #115, ADR-0033). An archive above 2 GB (REST reference p.118) is not sent over REST when the strategy was left to the rules: the vendor tools take it when this machine has an installation, and planning is refused (exit 2) naming `--strategy rest` when it has none; `--strategy rest` is the operator's decision and only warns. When the sidecar records a source version whose major differs from this server's, themes are not imported and the plan says so; `--themes` imports them, `--skip-themes` states the default and the two together are a usage error. Without a sidecar or with a version that does not parse the themes are left as the flags say.

### 9.4 Import safety

- `PreImportSnapshot` exports the affected subtree using the same strategy as the import (full server via vendor when `update=true` at root), with the server running (ADR-0040). An import into an organisation whose archive names no narrower folders snapshots `/organizations/<id>`, never the root.
- `import --no-snapshot` (ADR-0040) leaves out the snapshot export and its re-import: the backup phase is `RecordRepositoryListing` alone, the import phase's anchor is `RemoveImportAdditions` (`import.additions-rollback`), whose compensation deletes what the failed import created under the listed folders, and `import.new-content-rollback` stays. The plan carries `NO_SNAPSHOT_WARNING` in place of the rollback sentence (a failed import cannot put back what it overwrote), the rollback line says so, and the override is written to the audit table. Stored plan arguments gain `noSnapshot` (missing = false) and `snapshotStopsService` (missing = true); the fingerprint gains `snapshot=none` or `snapshot=live` only when the plan differs from the one an earlier jrs-upgrade would have built.
- `RecordRepositoryListing` (`backup.pre-import-listing`, right after the snapshot announcement, while the server is up) lists every URI under the snapshotted folders through `GET /rest_v2/resources?recursive=true` in pages and writes them to `runs/<runId>/pre-import-listing.txt`; a listing that cannot be taken fails the step before anything is imported (issue #100, ADR-0031).
- Rollback first deletes the resources the failed import created, the URIs under those folders that the listing did not hold, deepest first, then re-imports the snapshot with `update`, which puts back what the import overwrote. A resource anyone else creates under those folders between the listing and the failure is deleted with them, which the plan summary says in so many words. A missing listing, or a server that cannot be listed during the rollback, leaves the additions behind with a warning naming them; one resource that cannot be deleted is logged and the rest still go.
- Export planning asks the server whether each `--uri` exists (`GET /rest_v2/resources<uri>`) and refuses a plan naming one that does not, with exit 2, instead of producing an archive that holds only `resources/` and exiting 0 (field test 2, E3). Both export steps' postchecks refuse an archive with entries and no `index.xml`. A failed export task's cause is read from `errorDescriptor` when `message` is empty.
- The pre-import snapshot covers only the sidecar's folders that exist on this server; a folder the archive holds but the server does not have yet is named in the plan's warnings and not snapshotted, because importing new content is the ordinary case. Those folders have their own rollback (issue #139, ADR-0036): every import plan whose sidecar names folders carries the step `import.new-content-rollback`, whether or not they exist at plan time (so a plan rebuilt by `runs recover` after the import created them has the same steps); when it runs it records the topmost missing ancestor of each folder that does not exist right then in `runs/<runId>/new-content-roots.txt`, and its compensation deletes those roots with everything under them (`DELETE /rest_v2/resources<uri>`), deepest first. A root that is already gone is not an error, `/` is never recorded, an organisation's own folder is never recorded (resources cannot delete it; the run log names `DELETE /rest_v2/organizations/<id>`), and a deletion that fails ends the run with exit 4 naming the folders left. When none of the folders exists, the plan has no snapshot and no restore step, and its rollback sentence says which folders are deleted. A snapshot archive with entries but no `index.xml` therefore fails the snapshot step before anything is imported; the restore step still treats such an archive as nothing to put back (#41), which is now a defensive path.
- An import the server parks in phase `pending` (broken dependencies, or a catalog exported from another organisation; REST reference 10.1 pp.119-124) has imported nothing and never resumes by itself. `PollImport` cancels the task (`DELETE /rest_v2/import/{id}`) and fails `Recoverable`, naming the server's `error.code`, its `error.parameters` (the resource URIs) and the flag that gets past it; the snapshot re-import that follows puts back what is already there. `--broken-dependencies skip|include` is sent as the REST `brokenDependencies` query parameter and as js-import's `--broken-dependencies`; the default `fail` is the server's own and is never sent, so older servers see the request they always saw.
- The REST import start writes `import-started.txt` before `POST /rest_v2/import`. A 408, 429 or 503 answer means the server did not accept the request: the marker is removed and the start is retried. A 502 or 504, an unreachable server, or a marker without a task id on a later execute means the server may have accepted the import: the failure is fatal, nothing is uploaded again and the snapshot is not re-imported (ADR-0017, #44).

### 9.5 Commands

- `jrs-upgrade export [--uri ...] [--users-roles] [--access-events] [--full-server] [--strategy rest|vendor] [--key-alias <alias> | --portable] [--organization <id>] [--skip-dependent-resources] [--skip-favorite-resources] --out <file>`
- `jrs-upgrade import <archive> [--update] [--skip-user-update] [--skip-themes | --themes] [--broken-dependencies fail|skip|include] [--source-keystore ...] [--key-alias <alias>] [--organization <id> [--merge-organization]] [--strategy rest|vendor] [--force-version] [--no-snapshot] [--plan] [--yes]`

---

## 10. Upgrade subsystem

### 10.1 Modes and rollback semantics

- `--mode newdb` is the **default**. The vendor's `js-upgrade-newdb` **drops the repository database named in `default_master.properties`, recreates it with the target schema and imports the point-B full export into it** (its own `upgrade-newdb.help` lists those steps; ADR-0012). jrs-upgrade cannot undo that. `RunVendorUpgrade` passes the point-B full export as the script's argument; the vendor wrapper refuses to run without one.
- `--mode samedb` migrates the existing database schema in place. jrs-upgrade cannot undo that migration.
- Database backup is out of scope (§1.3). **`samedb` therefore requires `--db-backup-confirmed`** (audited; the gate says why: the in-place migration is not undone by an export), and its Plan summary states in plain text: "Rollback restores files only. Restore the database from your own backup before running rollback." **`newdb` asks for no backup** (ADR-0029): jrs-upgrade's own point-B full export, taken with the service stopped (ADR-0025), is the repository backup, and the newdb plan says so: "jrs-upgrade backs up the files, the keystore and a full export of the repository; js-upgrade-newdb drops and recreates the database from that export, and upgrade rollback --restore-database rebuilds it from the same export". A `--db-backup-confirmed` given to a newdb command is accepted and ignored.
- Rollback to point B restores webapp, keystore, configuration and buildomatic. After a `newdb` run, `upgrade rollback --restore-database` also rebuilds the repository database from the point-B export (the run's own `full-export.zip`, or the archive adopted with `--export`) with the restored buildomatic: `rebuild-database` (`js-ant init-js-db-<ce|pro>`, the target the newdb script itself runs first) then `reimport-full-export` (`js-import --input-zip <export> --update` with events and settings), both `irreversible()`, each run at most once per rollback run. It is refused (exit 2) unless `snapshots/<runId>/vendor-upgrade.started` says the vendor script was launched, which `RunVendorUpgrade` writes as it starts and no compensation erases, since a database the script never touched must not be dropped; and refused (exit 6) for a samedb run. Without the flag the rollback is files only and its plan names the export and the flag (ADR-0029).

### 10.2 Orchestration plan

**Phase A — preflight**
1. `Doctor` (must pass).
2. `VerifyTargetPackage` — target JRS distribution present, checksum verified, version in compat matrix as a supported upgrade path from current **in the chosen mode**, `vendor.javaHome` is one of the target's Java majors, and the Tomcat that will host the target (`--tomcat-dir`, else `server.tomcatDir`) is certified for it by the matrix's `tomcat` ranges; a Tomcat whose version cannot be read is a plan warning (review §2.1, ADR-0026). Two rules of the upgrade guides about `default_master.properties` that the matrix cannot express are judged on what `WriteMasterProperties` will stage (the target package's own file with the installed keys, minus passwords, appended): the installation type never changes in an upgrade, so a target file that would turn a Compact installation into a Split one or back (`installType`, absent meaning compact; an `audit.*` key the installed file lacks) is refused with exit 6 while planning, Compact to Split being the vendor's separate `js-migrate-to-split-*` procedure (upgrade guide 10.1 pp.12-14, 64-65); and an Oracle repository (`dbType=oracle`) upgraded to 10.1 or later must name `dbVersion` on one side or the other, else the precheck fails (upgrade guide 10.1 p.43; review §1.3).
2a. `VerifyVendorPreconditions` — review §2.5 (issue #108), read-only, before anything is stopped or backed up: for a 10.0+ commercial target `jaspersoft.jrs.license` must be in the home of the user running jrs-upgrade (upgrade guide 10.1 pp.43-44; FAIL when it is nowhere, WARN naming the copy to make when it is only under `server.installDir` or the `js.license.directory` the host Tomcat's `setenv` names); for `dbType` oracle, sqlserver or db2 the driver jar named by `maven.jdbc.artifactId`-`maven.jdbc.version` (installed master properties first, else the target's) must sit in the target buildomatic's `conf_source/db/<dbType>/jdbc/` (FAIL naming the copy from the installed buildomatic when it has the jar, else the download and the property edit; release notes 10.1 p.33); and the target webapp's `WEB-INF/js.password-storage-config.properties`, when it differs from the running webapp's or the running webapp has none, is a WARN pointing at `--migrate-passwords` (installation guide 10.1 pp.194-199).
3. `ConfirmDbBackup` — both modes (ADR-0012); fails without `--db-backup-confirmed`.

**Phase B — backup** (rollback point B)
4. `FullExport` (vendor strategy; includes service stop/start) — **samedb only**. In `newdb` mode the export moves to Phase C, after the stop (ADR-0025): the database is rebuilt from it, so nothing may change in the repository between the export and the vendor run, and the service is not restarted in between.
5. `BackupKeystore`.
6. `BackupWebapp` — archive of `tomcatDir/webapps/<webappName>` and installed `buildomatic/`.
7. `BackupConfig` — `default_master.properties`, JNDI, context files.

**Phase C — vendor upgrade** (rollback point C = restore B)
8. `WriteMasterProperties` — into the target package's buildomatic dir (§7.4), snapshotting any existing file.
8a. `StageKeystoreInit` — writes `keystore.init.properties` (`ks`, `ksp`) into the target buildomatic: a verbatim copy of the installation's own when it has one, else the directories where the adapter found `.jrsks` and `.jrsksp`. Without it the vendor script, which jrs-upgrade runs as its own account, finds no keystore and `setup.xml`'s `create-ks` makes a new one (silently unless `BUILDOMATIC_MODE=interactive`), after which no password in the repository can be decrypted. The precheck refuses when no location is known; a pre-existing file is snapshotted and restored by compensation (security guide 10.1 pp.11-13; review §1.4).
9. `StopService`.
9a. `FullExport` — `newdb` only (ADR-0025); with the service stopped, into the same snapshot set as the other point-B artefacts. With `--export <file>` the step is `AdoptFullExport` instead (ADR-0028): the file must exist and be an export archive (root `index.xml`); its SHA-256 and path are recorded in `snapshots/<runId>/full-export.external`, nothing is copied, and the record is what `RunVendorUpgrade` passes to the script after re-verifying the hash. A sidecar naming another server or version is a plan warning, never a refusal (field test 2: an upgrade is not always linear); the plan always warns that every repository change made after that export is discarded. `--export` with `samedb`, or `--key-alias`/`--key-password-ref` without `--export`, is a usage error (exit 1).
9b. `CopyWebappToTomcat` — with `--tomcat-dir` only (ADR-0026): copies `webapps/<name>` into the new Tomcat, which `appServerDir` in the staged `default_master.properties` then names; compensation removes the copy, the old Tomcat is never touched. `--tomcat-dir` is accepted only with `service.kind: manual`; other kinds are refused at plan time (exit 2) because the registered service would start the old Tomcat, and the refusal's remediation carries the platform's own re-registration steps for the configured kind with the real paths and name (a Windows service, a systemd unit, a `catalina` script, a `ctlscript`); with `manual` the plan summary carries the same steps for this operating system's registered kind (ADR-0026 amendment, issue #109). The plan also warns when the host Tomcat is of major 10 or later and its `bin/setenv.sh|bat` carries no `--add-opens`, naming the file: the installation guide 10.1 (pp.84-86) lists the options for Java 17 and 21, but the vendor's bundled installer starts without them, so this is advice, not a refusal.
10. `RunVendorUpgrade` — `js-upgrade-newdb <point-B full export>` or `js-upgrade-samedb`; streamed output; `JAVA_HOME=vendor.javaHome`. A package without the wrapper gets what the wrapper runs: `js-ant upgrade-minimal-<ce|pro>` with `-Dstrategy=standard -DimportFile=<export>` or `-Dstrategy=inDatabase`.
10-events. `ImportEvents` — newdb with `--include-events` only (issue #106): `js-import --input-zip <point-B full export> --include-access-events --include-audit-events --include-monitoring-events` through the target buildomatic while the server is still down, with `--keyalias` when the export was adopted with one. "Starting version 7.9.0, the js-upgrade-newdb script does not import the access, audit, monitoring data" (upgrade guide 10.1 p.80); the installation guide p.256 gives this import as the remedy. The point-B export already carries the three event kinds. Once per run through a marker; `irreversible()`: the rows belong to the database the newdb rollback rebuilds from the same export (ADR-0029). Without the flag a newdb plan's summary states that the events are left behind and names the flag and the vendor command; samedb migrates in place and keeps them.
10-passwords. `MigratePasswords` — samedb with `--migrate-passwords` only, target 10.1.0 or later (issue #108; refused with exit 2 for an older target, ignored with a warning for newdb): `js-ant migrate-passwords-dry-run`, then `js-ant migrate-passwords` through the target buildomatic while the server is still down (installation guide 10.1 pp.194-199); once per run through a done marker; `irreversible()`: the guide's only way back is the database restore `--db-backup-confirmed` vouched for; the utility skips users already in the modern format, so a re-run is safe.
10a. `ClearTomcatCaches` — empties `<tomcatDir>/work` and `<tomcatDir>/temp` (the vendor's "Additional tasks", upgrade guide 10.1 pp.34-36); `irreversible()`: Tomcat regenerates both on start.
10b. `ClearRepositoryCache` — `update JIRepositoryCache set item_reference = null; delete from JIRepositoryCache` through the configured database (the vendor's remedy for `local class incompatible`); `irreversible()`: the cache is rebuilt on demand. Best effort: without a `database` section, or on a JDBC failure, it warns with the two statements to run by hand rather than roll a finished vendor upgrade back to point B (review §2.2).
11. `StartService` + `WaitForServer`.

**Phase D — reconcile** (report-first; nothing mutates without confirmation)
12. `PlanHotfixReapply` — for each installed hotfix: if `applies` matches the new version *and* every `replaces` target exists in the new webapp, it is listed as `REAPPLICABLE`; otherwise `SUPERSEDED`. The list is shown; in interactive mode the operator confirms, with `--yes` only `--reapply-hotfixes` triggers re-application. Re-application runs the normal apply Plan per hotfix.
13. `PlanCustomizationReapply` — for each registered customization, a 3-way comparison of the original (hash at registration), the customized copy (snapshot), and the new file. If new equals original, the customization is reapplied automatically. Otherwise it is reported as `CONFLICT` with a unified diff and left for the operator.

**Phase E — verify**
13a. `CheckAnalyticsJndi` — 9.0.x targets only (issue #108): the deployed webapp's `META-INF/context.xml` must declare `jdbc/jasperserverSystemAnalytics` and `jdbc/jasperserverAuditAnalytics` "even if the feature is disabled" (release notes 9.0 p.15); read-only, a missing resource is a logged WARN naming the file, never a failure.
14. `Smoke` (§12.2). Failure offers rollback to point B.
15. `RecordUpgrade` — records the upgrade snapshot set as retention-protected (it no longer marks hotfixes `SUPERSEDED`, ADR-0004). `PointConfigAtTarget` then points `server.buildomaticDir` at the target's buildomatic and, with `--tomcat-dir`, `server.tomcatDir` at the new Tomcat.

**Rehearsal** (`jrs-upgrade upgrade … --test`; field test 2, U1). The vendor's own validation, run before anything is touched: phase A as above (`Doctor`, `VerifyTargetPackage`), then `WriteMasterProperties` and `StageKeystoreInit` exactly as the upgrade stages them, then `RunVendorTest` — `js-upgrade-<mode> test` when the package ships the wrapper (the vendor's `test` option runs `pre-upgrade-test-<ce|pro>`: it validates the properties, the database connection and the package and, by the vendor's own word, modifies no instance and no resource; with `test` the newdb wrapper takes no export file, buildomatic `bin/do-js-upgrade`), else `js-ant pre-upgrade-test-<ce|pro> -Dstrategy=<standard|inDatabase>` — then `UnstageTargetPackage`, which runs the two staging steps' compensations so the package is left as it was found. `RunVendorTest` is read-only; Ant's `BUILD FAILED` or the keystore banner fail it with the vendor's lines in the message. No stop, no backup, no export; the plan's operation is `upgrade.test`, its summary says so, and the samedb backup gate does not apply. A failed rehearsal exits **2**: nothing was mutated (the staged files are removed by the ordinary compensation), so 3 would claim a rollback of the server that never happened. The guided menu offers the rehearsal before the real run.

### 10.3 Customizations

- `jrs-upgrade customizations register <path>` snapshots the file and records its current hash as "original" in the `customizations` table. `unregister`, `list`, and `diff` are provided. `scan --vendor <path>` compares the installed webapp with the vendor's copy (#72); `--tomcat` adds the Tomcat-side files an upgrade does not carry over (`bin/setenv.*`, `conf/server.xml`, `conf/Catalina/localhost/*.xml`, non-Tomcat `lib/*.jar`), listed without any comparison (ADR-0034). Files under the webapp's `scripts/` are an overlay of the `jasperserver-ui` project, not files: the scan notes it and `register` warns.

### 10.4 Commands

- `jrs-upgrade upgrade --to <version> --package <path> [--mode newdb|samedb] [--db-backup-confirmed] [--reapply-hotfixes] [--tomcat-dir <dir>] [--export <file>] [--key-alias <alias>] [--key-password-ref <ref>] [--include-events] [--migrate-passwords] [--test] [--plan] [--yes]`
- `jrs-upgrade upgrade rollback <runId> --to-point B|C [--restore-database]`
- `jrs-upgrade customizations register|unregister|list|diff <path>`

---

## 11. Security

### 11.3 Redaction and audit

- As §5.8. Audit rows for: every run start/end, every override flag (`--allow-unsigned`, `--allow-unsupported`, `--db-backup-confirmed`), key ring changes, config changes, isolated-mode refusals.

### 11.4 Least privilege

- `doctor` warns if running elevated without a Step that requires it, and FAILs if `server.runAsUser`'s home is unreadable when keystore access is required.
- A home jrs-upgrade creates is private to its owner on both operating systems: `rwx------` on Linux, one inheritable owner entry and a protected DACL on Windows. An existing home is left as the operator set it up (#50).
- The vendor tools do not inherit any `JRS_UPGRADE_*` variable nor any variable a configured `env:` secret reference names (ADR-0019, #47).
- In token mode, `server.auth.tokenLocation: header` sends the pre-authentication token as the `pp` request header instead of a URL parameter (ADR-0018, #45). The form login body is encoded from the password's `char[]` without a `String` copy. The source keystore password of `import --source-keystore` still reaches `js-import` as `--storepass`, the only form the vendor tool accepts, and is visible in the process list while it runs (ADR-0020, #51).
- The capability probe decides `REST_LOGIN` from the compat matrix for every listed version and sends a login without credentials only to an unlisted one; form login detects the endpoint with the credentialed login itself (#46).

---

## 12. Detection and diagnostics

### 12.0 `init`

- `jrs-upgrade init [--yes] [--non-interactive]` detects the installation and, after confirmation or with `--yes`, writes `config.yaml` (`--non-interactive` alone reports and exits 2 rather than writing):
  - candidate install dirs from `Platform.candidateInstallDirs()` (common paths, the working directory of a running Tomcat process, the Windows registry/uninstall entries);
  - `webappName`, `tomcatDir`, `baseUrl` from the Tomcat layout and `server.xml` port;
  - `service.kind` and name by probing Windows services / systemd units, then `ctlscript`, then the Tomcat's `bin/catalina`, else `manual`; the reason shown says that no registered service is usual for a WAR + buildomatic install and what jrs-upgrade will run (ADR-0042);
  - `buildomaticDir` from `--buildomatic-dir` or the 7.4 search, shown with its source (a hint that cannot be reached is reported, not written);
  - `vendor.javaHome` from the installation, and the `database.passwordRef` placeholder; `database.type`, `database.url` and `database.username` are shown from that buildomatic directory's `default_master.properties` but not copied, because every command reads them from it when `config.yaml`, the environment or `--set` leave them out (#73; passwords are never read);
  - `runAsUser` from the Tomcat process owner;
  - `server.auth.username` `superuser` for the commercial edition (`jasperserver-pro`) and `jasperadmin` for the community edition (#59).
- Every value is shown for confirmation; nothing is written without it unless `--yes`.
- Interactively (no `--yes`, `--non-interactive` or `--json`) the operator may replace the reviewed values one by one; each is validated as a `--set` override is, and a directory must exist. `init` then offers to store the server and database passwords in `secrets.enc` (read without echo into zeroed `char[]`s; a new store's passphrase is entered twice unless `--passphrase-file` or `JRS_UPGRADE_PASSPHRASE` supplies it) and writes `enc:` references for them; the secrets are stored only after the write is confirmed and before `config.yaml` is written. Otherwise `passwordRef` placeholders are `env:` references (#63).

### 12.1 `doctor`

Text rendering, shared with `smoke` and `hotfix verify`: items sorted FAIL, WARN, PASS, SKIP; the problems, then a `----` rule, then the rest; a closing line naming the problems (`1 fail (server), 0 warn, 21 pass, 2 skip`) coloured by the worst status (field test 2, D2).

Checks (each returns PASS/WARN/FAIL with remediation text): bundled runtime integrity; config schema; secret file permissions; server reachable; auth works; version/edition/tenancy detected; compat matrix match; capability probes vs. expected; install dir layout; service controller can query state; `running-tomcat`: the pid of a Tomcat running under the layout's Tomcat directory, from the process list whatever `service.kind` says, WARN when the configured service reports STOPPED while it runs or RUNNING while none runs, a process whose command line cannot be read (a JVM, or a `tomcatN.exe` service wrapper) that listens on its `server.xml` ports is named as possibly it, PASS when the configured service reports RUNNING and WARN otherwise, SKIP when the scan cannot run (ADR-0042, issue #147); write access to target dirs; disk space; keystore present and readable for `runAsUser`; vendor scripts present in the 7.4 buildomatic directory (WARN on a Windows UNC path, which `cmd.exe` refuses as a working directory); `vendor.javaHome` version matches matrix; database connectivity (if configured); pending runs; run lock free; snapshot store health; network mode consistency (isolated mode with proxy configured = WARN); running elevated without need = WARN; Tomcat version at `server.tomcatDir` (else the detected layout) certified for the detected JRS version per the matrix's `tomcat` ranges, SKIP when neither `lib/catalina.jar` nor `RELEASE-NOTES` gives a version (review §2.1), WARN when a certified Tomcat of major 10 or later has no `--add-opens` in its `bin/setenv.sh|bat` (installation guide 10.1 pp.84-86, advice only; issue #109). The licence's clustering flag: `cluster` is WARN when `licenseFeatures` says `cl: true` (jrs-upgrade changes this node only; on purpose also on a single-node commercial server, whose bundled licence carries the flag) and PASS otherwise; the `auth` line says, when the pre-authentication token travels in the `pp` header, that the header is jrs-upgrade's choice (ADR-0018) where the REST reference documents `pp` as a URL parameter only (issue #112). Six more with vendor backing (issue #113, ADR-0032): `telemetry` is WARN when `WEB-INF/js.config.properties` sets `heartbeat.enabled=true` (usage telemetry upload; the administrator guide, relevant in isolated mode; a cumulative hotfix can set it, so `hotfix apply` warns when the package lays the file down or the switch is already on), SKIP when the file is absent; `audit` is WARN when `feature.audit_monitoring.enabled=true` (event exports can be very large, administrator guide pp.250, 416, and a newdb upgrade loses the events unless `--include-events` is given); `database-service` is WARN when the bundled `jasperreportsPostgreSQL` service registered beside the Tomcat one is not running (installation guide p.51; the start-service step starts it before Tomcat), PASS when it runs, when there is none, or for a script or manual kind; `pid-file` is WARN when `<tomcat>/temp/catalina.pid` names a process that is gone (installation guide p.237; the start script removes a stale one); `auth` is WARN when basic authentication carried non-ASCII credentials and the login still worked, and its FAIL names the login form when it did not (REST API reference p.24); and a systemd unit named `X.socket` is accepted throughout (AWS guide p.30): `init` prefers it over `X.service`, its state is the service's, and stop and start act on both.

`doctor` never needs the admin password and never prompts (field test 2, D1). The server's identity is fetched without a credential (`serverInfo`; a server that wants a login for it gets the configured one, resolved then), so `server`, `identity`, `compat`, `vendor-java` and `tomcat` run whenever the server answers. The three items that log in, `auth`, `capabilities` and `keystore` (which asks the server for its capabilities), are SKIP with "no admin password available" and a remediation naming the reference when the password is not at hand without a prompt: an unset `env:` variable, a missing `file:`, or an `enc:` entry whose store has no passphrase from `--passphrase-file` or `JRS_UPGRADE_PASSPHRASE`. The `secrets` item reports such a reference as WARN rather than FAIL, and `database` is SKIP when `database.passwordRef` is configured but not supplied; a reference that is present but wrong (a world-readable file, a store that does not decrypt) stays a FAIL. The REST adapter resolves `server.auth.passwordRef` on the first request that carries it, never at connect time. An upgrade's own doctor precheck treats a skipped `auth` as a failure, since the vendor run and the export log in.

### 12.2 `smoke`

Non-mutating by default: login; serverInfo; list `/` repository; run `smoke.reportUri` to PDF and verify the response starts with `%PDF` and exceeds 1 KB (WARN, not FAIL, if the report URI is absent); scheduler API reachable; export of a tiny subtree round-trips. `--mutating` uploads a bundled minimal JRXML under `/temp/jrs-upgrade`, runs it, and deletes it.

### 12.3 `selfcheck`

Verifies every jar in the runtime image against a build-time manifest of hashes, runtime version, config schema, key ring, SQLite schema version, the host operating system and architecture against ADR-0002, and the directory the SQLite native library is unpacked into (a `noexec` mount fails the item). A failing platform item exits 6, not 2.

### 12.4 `runs support-bundle`

`jrs-upgrade runs support-bundle <id> [--out <zip>] [--json]` (§6.6) writes one run's support bundle as a zip, meant to be attached to a support ticket.

- Entries: `run.json` (the `runs show --json` document: steps, statuses, durations, failure block, and the stored plan when the run has one, with the plan's `{runId}` placeholders filled in with the run's id; there is no separate `plan.json`, #161); `transitions.jsonl` (every `step_transitions` row of the run, one JSON object per line); `server.json` (server identity, or `reachable: false` with the probe's error when the server cannot be reached — a bundle is wanted most when the server is broken, so this entry never causes a failure); `doctor.json` (a fresh `doctor` run, never a cached one); `config-redacted.yaml` (the effective configuration with secret references, never values); `logs/<name>` (the run's own lines of the log file named by the `jrs-upgrade.log.file` system property, the log this process is actually writing: every line whose `runId` field is the run's, and every line without a `runId` written between the run's start and end, at most the last 2,000; the runner puts `runId` on each line it writes while a run executes, #160; every line a vendor tool prints (`js-export`, `js-import`, `js-ant`), redacted and with the run's `runId`, is among them under the logger `com.jaspersoft.jrsupgrade.jrs.vendor.output`, because the vendor wrappers write only their `validate-keystore` step to the buildomatic log, #200) and `logs/tail-<name>` (the last 200 lines of that log, for what happened around the run); and, under `vendor/`, the newest buildomatic script log last written while the run executed (from its start to two minutes after its end; a log from before the run or from a later run belongs to another vendor run and is never bundled, #161), `jasperserver.log`, the Tomcat `catalina` log, `installation.log` and `default_master.properties` (with password keys blanked before the redactor sees the line), each tail-capped at 5 MB.
- Every byte written passes the redactor on its way into the archive, so a registered secret cannot appear in the bundle in any encoding the redactor knows.
- Everything that can fail — the live doctor run and the server probe behind it, and every document read from the state store — runs before the archive file is created, so a failure is a clean refusal and never a truncated or zero-byte zip.
- A missing optional input (no stored plan, no vendor file at a given location) yields no entry rather than an error.
- A write that fails partway through the archive leaves no file behind: the target is opened `CREATE_NEW`, and any exception while writing deletes the partial file before it propagates.
- `--out` defaults to `<id>-support-bundle.zip` in the current directory.
- Exit 2: a given `--out` that already exists, names a directory or has no parent directory is refused before anything is read from the state store; the run id is then looked up (part of an id is accepted, several matches exit 1), and the default `--out`, which needs the full id for its name, is checked after that lookup; an unknown run id is exit 2.

---

## 15. Coding conventions

- Java 21 (the tool bundles its own runtime; the server's Java is irrelevant to jrs-upgrade code), `-Werror`, Error Prone enabled, Spotless (google-java-format).
- Records for immutable data; sealed interfaces for result/error/event types; pattern-matching `switch` over sealed types with no `default` branch so new variants fail compilation.
- No `null` returns from public APIs; use `Optional` or sealed results.
- All I/O through `Platform`. No `java.io.File`. No whole-file byte arrays.
- Every public class has a one-paragraph Javadoc stating its invariants.
- Test naming: `should_<behaviour>_when_<condition>`.
- Commit messages: Conventional Commits. One logical change per commit.
- Branch per phase; PR description must list which acceptance criteria are satisfied.

---

## 18. Exit codes

| Code | Meaning |
|---|---|
| 0 | success (override flags such as `--allow-unsupported` do not change the code; they are audited) |
| 1 | usage error |
| 2 | precheck/doctor/fingerprint failure (nothing mutated) |
| 3 | run failed, rolled back cleanly |
| 4 | run failed, rollback incomplete — manual action required (details printed) |
| 5 | cancelled |
| 6 | unsupported server/config (compat matrix), or a host outside ADR-0002 (not Windows or Linux on x86-64) |
| 7 | signature/verification failure |
| 8 | pending recovery required — run `jrs-upgrade runs recover <runId>` |
| 9 | run lock held by another jrs-upgrade process |

---

