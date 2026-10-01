# Recorded servers

One directory per JasperReports Server the adapter contract runs against without a container
(ADR-0011). `RecordedJrsContractTest` replays every directory through the real `RestJrsAdapter`
and fails when a compat-matrix row (`core/src/main/resources/compat/matrix.yaml`, range × edition)
has no recording, so adding a row to the matrix means adding a recording here.

## Layout

```
recordings/<version>-<edition>/
  recording.json      version, edition, tenancy, context path, source, recordedAt, note
  mappings/*.json     WireMock stub mappings (request url + method, response status/headers/body)
  __files/            response bodies WireMock chose to keep out of line (large ones only)
```

`source` is `recorded` (captured from a live server by `RecordJrsTest`) or `synthesised` (the
serverInfo body is the fixture captured from a real server of that version under
`../fixtures/`; the probe and health answers are what the matrix expects of the row, not
observations). A synthesised recording proves the adapter and the matrix agree on the row; a
recorded one proves the server does too. Replace synthesised recordings with recorded ones as
servers become available; the metadata says which is which.

## What a recording must answer

The contract calls `identity()`, `capabilities()` and `health()` with Basic authentication, which
issues: `GET <ctx>/rest_v2/serverInfo`; `GET <ctx>/rest_v2/export/jrs-upgrade-probe/state` and
`.../import/jrs-upgrade-probe/state` (200 or 404 means the async API exists); `GET
<ctx>/rest_v2/organizations?limit=1` (200/204 means multi-tenant); `POST <ctx>/rest_v2/login`
with an empty body (anything but 404 or 5xx means the REST login exists); `GET
<ctx>/rest_v2/resources?folderUri=%2F&recursive=false&limit=1` (the authentication check) and
`...&limit=100` (the repository root); `GET <ctx>/rest_v2/jobs` (the scheduler).

## Recording a live server

From bash on either OS (PowerShell splits dotted `-D` arguments):

```
JRS_UPGRADE_RECORD_PASSWORD=... scripts/mvn.sh -pl jrs -am test -Dtest=RecordJrsTest \
    -Dgroups=needs-jrs -Dexcluded.groups= -Dsurefire.failIfNoSpecifiedTests=false \
    -Djrs-upgrade.record.baseUrl=http://host:port/jasperserver-pro
```

The recorder proxies the contract calls through WireMock, keeps only what the server answered,
strips request header, cookie and body matchers on the login paths and every `Set-Cookie`, and
refuses to write a recording when the live server fails the contract. Add
`-Djrs-upgrade.record.name=<name>` to choose the directory name (default `<version>-<edition>`).
No credential is ever written: `RecordedJrsContractTest` scans every file.
