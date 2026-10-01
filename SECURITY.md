# Security policy

## Supported versions

jrs-upgrade has no numbered releases yet. Security fixes go to `main`; build the jar from the latest `main` to get them.

## Reporting a vulnerability

**Do not open a public issue, discussion or pull request for a security problem.**

Report it privately through GitHub: the repository's **Security** tab → **Report a vulnerability** ([direct link](https://github.com/robertgorsuch/jrs-upgrade/security/advisories/new)). The report reaches the maintainers only.

Please include:

- the jrs-upgrade version (`java -jar jrs-upgrade.jar --version`) or the commit you built from;
- the operating system, and the JasperReports Server version involved;
- what an attacker can do, and the steps or input that show it;
- a support bundle (`jrs-upgrade runs support-bundle <id>`) when a run is involved. It is redacted, but review host names and paths before attaching it.

## What happens next

- **Acknowledgement:** you will get one before anything is discussed in public.
- **Fix before disclosure:** a fix is prepared in a private advisory, and the report is published together with the fixed version, crediting you unless you ask otherwise.
- **Outside this repository:** a problem in JasperReports Server itself, or in the vendor's buildomatic scripts, belongs to Jaspersoft. Tell us anyway if jrs-upgrade makes it worse.

## Scope

The security model is described in [`docs/security.md`](docs/security.md) (`jrs-upgrade docs security`). It covers:
- the threat model;
- how secrets are stored and redacted;
- what support bundles contain;
- how packages, WARs, jars and override files are read without being unpacked, loaded or resolved against the network.
