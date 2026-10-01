package com.jaspersoft.jrsupgrade.app;

import com.jaspersoft.jrsupgrade.core.engine.Plan;
import com.jaspersoft.jrsupgrade.core.secrets.SecretRef;
import com.jaspersoft.jrsupgrade.jrs.api.BrokenDependencies;
import com.jaspersoft.jrsupgrade.jrs.api.ExportImportStrategy;
import com.jaspersoft.jrsupgrade.ops.PlanRegistry;
import com.jaspersoft.jrsupgrade.ops.Services;
import com.jaspersoft.jrsupgrade.ops.StrategyFlag;
import com.jaspersoft.jrsupgrade.ops.exim.ExportImportOperations;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * {@code jrs-upgrade import <archive> [--update] [--skip-user-update] [--access-events]
 * [--audit-events] [--monitoring] [--settings] [--skip-themes] [--source-keystore <path>]
 * [--source-keystore-password-ref <ref>] [--strategy rest|vendor] [--force-version] [--no-snapshot]
 * [--plan] [--yes] [--json]} (spec §9.5). Invariants: the import only ever runs through {@link
 * PlanExecutor}; the plan takes a pre-import snapshot first, with the server running, unless {@code
 * --no-snapshot} is given (audited; ADR-0040), and its summary states what rollback can and cannot
 * put back (spec §9.4); an unparseable secret reference is a usage error (exit 1) raised before any
 * bootstrap; a planning failure exits 2 because nothing has been touched yet, which includes an
 * archive from 10.1 or later refused for an older server without {@code --force-version} (issue
 * #107).
 */
@Command(
    name = "import",
    mixinStandardHelpOptions = true,
    exitCodeOnInvalidInput = ExitCodes.USAGE,
    description =
        "Import an export archive after snapshotting the affected subtree; rollback re-imports"
            + " the snapshot (best effort).")
final class ImportCommand implements Callable<Integer> {

  @Spec CommandSpec spec;
  @Mixin GlobalOptions global;

  @Parameters(index = "0", paramLabel = "<archive>", description = "Export archive to import.")
  Path archive;

  @Option(names = "--update", description = "Overwrite resources that already exist.")
  boolean update;

  @Option(
      names = "--skip-user-update",
      description = "Do not update users that already exist (with --update).")
  boolean skipUserUpdate;

  @Option(names = "--access-events", description = "Include access events.")
  boolean accessEvents;

  @Option(names = "--audit-events", description = "Include audit events.")
  boolean auditEvents;

  @Option(names = "--monitoring", description = "Include monitoring events.")
  boolean monitoring;

  @Option(names = "--settings", description = "Include server settings.")
  boolean settings;

  @Option(
      names = "--skip-themes",
      description =
          "Do not import themes. This is already the default when the archive comes from another"
              + " major version than this server (old themes may not fit the new one).")
  boolean skipThemes;

  @Option(
      names = "--themes",
      description =
          "Import the themes even when the archive comes from another major version than this"
              + " server.")
  boolean themes;

  @Option(
      names = "--broken-dependencies",
      paramLabel = "fail|skip|include",
      description =
          "What to do with a resource whose dependency is missing: fail before importing anything"
              + " (the server default), skip the resource, or include it with the dependency"
              + " missing.")
  String brokenDependencies;

  @Option(
      names = "--source-keystore",
      paramLabel = "<path>",
      description = "The source server's .jrsks, imported first when the keystores differ.")
  Path sourceKeystore;

  @Option(
      names = "--source-keystore-password-ref",
      paramLabel = "<ref>",
      description = "Secret reference (env:NAME, file:/path, enc:NAME) of the keystore password.")
  String sourceKeystorePasswordRef;

  @Option(
      names = "--strategy",
      paramLabel = "rest|vendor",
      description = "Force the REST or vendor CLI strategy instead of selecting one.")
  String strategy;

  @Option(
      names = "--key-alias",
      paramLabel = "<alias>",
      description =
          "Decrypt the archive with this key of this server's keystore (the alias it was exported"
              + " with, e.g. deprecatedImportExportEncSecret, the Legacy key) instead of the"
              + " server's own import/export key. Read from the sidecar when it records one.")
  String keyAlias;

  @Option(
      names = "--organization",
      paramLabel = "<id>",
      description =
          "Import into this organisation. The id should match the one the archive was exported"
              + " from; when it does not, add --merge-organization.")
  String organization;

  @Option(
      names = "--merge-organization",
      description =
          "With --organization: merge the archive's organisation into the target one when their"
              + " ids differ (the archive's users, roles and resources override same-named ones).")
  boolean mergeOrganization;

  @Option(
      names = "--force-version",
      description =
          "Import an archive exported from JasperReports Server 10.1 or later into an older"
              + " server anyway; the vendor says such resources cannot be imported there, so the"
              + " plan refuses it without this flag (audited).")
  boolean forceVersion;

  @Option(
      names = "--no-snapshot",
      description =
          "Do not export the affected folders before importing. A failed import can then not put"
              + " back what it overwrote; it only deletes what it created (audited).")
  boolean noSnapshot;

  @Option(names = "--plan", description = "Show the plan and exit without running it.")
  boolean plan;

  @Override
  public Integer call() {
    PrintWriter out = spec.commandLine().getOut();
    PrintWriter err = spec.commandLine().getErr();
    Optional<ExportImportStrategy.Kind> kind;
    Optional<SecretRef> passwordRef;
    BrokenDependencies broken;
    try {
      kind = StrategyFlag.parse(strategy);
      broken =
          brokenDependencies == null
              ? BrokenDependencies.FAIL
              : BrokenDependencies.parse(brokenDependencies);
      passwordRef =
          sourceKeystorePasswordRef == null
              ? Optional.empty()
              : Optional.of(SecretRef.parse(sourceKeystorePasswordRef));
    } catch (IllegalArgumentException e) {
      return ExitCodes.fail(out, err, global.json(), ExitCodes.USAGE, e.getMessage());
    }
    if (mergeOrganization && organization == null) {
      return ExitCodes.fail(
          out,
          err,
          global.json(),
          ExitCodes.USAGE,
          "--merge-organization needs --organization <id>");
    }
    if (themes && skipThemes) {
      return ExitCodes.fail(
          out, err, global.json(), ExitCodes.USAGE, "--themes and --skip-themes contradict");
    }
    ExportImportOperations.ImportOptions options =
        new ExportImportOperations.ImportOptions(
            archive,
            update,
            skipUserUpdate,
            accessEvents,
            auditEvents,
            monitoring,
            settings,
            skipThemes,
            Optional.ofNullable(sourceKeystore),
            passwordRef,
            kind,
            broken,
            Optional.ofNullable(keyAlias),
            Optional.ofNullable(organization),
            mergeOrganization,
            forceVersion,
            themes,
            false,
            noSnapshot);
    try (Bootstrap boot = Bootstrap.open(global, Env.vars(), Clock.systemUTC())) {
      Services services = boot.services();
      Plan planned;
      try {
        planned = EximOps.open(services).planImport(options);
      } catch (RuntimeException e) {
        return ExitCodes.reportPlanningFailure(out, err, global.json(), e);
      }
      PlanExecutor executor = new PlanExecutor(services, global, out, err, Env.vars());
      return executor.execute(
          new PlanExecutor.Request(
              planned, PlanRegistry.IMPORT, PlanRegistry.importArgs(options), plan, false));
    }
  }
}
