# `--json` output schemas (spec §14 Phase 8)

Every leaf command of `jrs-upgrade` accepts `--json`. Its standard output is then machine-readable and
validates against the JSON Schema (draft 2020-12) named here; standard error stays silent.
`com.jaspersoft.jrsupgrade.app.JsonSchemas` maps each command path to its schema and is the authority
the tests enforce (a command without a schema fails `JsonOutputSchemaTest`).

Two layouts exist:

- **Document** — exactly one JSON document, e.g. `doctor`, `customizations list`, `runs show`.
  `config show` validates against `core`'s `schema/config.schema.json`.
- **Stream** (every command that runs a `Plan`: `export`, `import`,
  `upgrade`, `upgrade rollback`, `runs recover`) — JSON Lines, one document per line:
  1. the plan (`plan.schema.json`; `runs recover` rebuilds it and does not print it; with `--plan`
     it is the only line),
  2. one `Event` per line (`events.schema.json`, discriminated by `type`),
  3. exactly one closing line: `{"outcome": ...}` (`outcome.schema.json`) or `{"error": ...}`.

Any command that refuses or fails prints a single `error.schema.json` document instead:

```json
{"error": {"class": "SignatureFailed", "message": "...", "exitCode": 7, "remediation": "..."}}
```

`error.exitCode` equals the process exit code (spec §18); `class` is the exception's simple name or
the exit-code category (`UsageError`, `PrecheckFailed`, `SignatureFailed`, `RecoveryRequired`,
`LockHeld`, `Unsupported`, `Cancelled`). `details` is an optional object (for example
`pendingRuns` on exit 8).

Every schema has an `$id` under `https://jaspersoft.com/jrs-upgrade/`; cross-references (`run`,
`report-item`, `plan`, `config`) resolve to the files in this directory (or `schema/` for the
configuration) without network access — see `JsonSchemas.resourcePath`.
