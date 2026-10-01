package com.jaspersoft.jrsupgrade.app;

import com.jaspersoft.jrsupgrade.core.engine.Plan;
import com.jaspersoft.jrsupgrade.jrs.api.ExportImportStrategy;
import com.jaspersoft.jrsupgrade.jrs.api.ExportRequest;
import com.jaspersoft.jrsupgrade.ops.PlanRegistry;
import com.jaspersoft.jrsupgrade.ops.Services;
import com.jaspersoft.jrsupgrade.ops.StrategyFlag;
import com.jaspersoft.jrsupgrade.ops.exim.ExportImportOperations;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * {@code jrs-upgrade export [--uri <uri>]... [--users-roles] [--access-events] [--audit-events]
 * [--monitoring] [--settings] [--full-server] [--strategy rest|vendor] --out <file> [--plan]
 * [--yes] [--json]} (spec §9.5). Invariants: the archive and its {@code .jrs-upgrade.json} sidecar
 * are the only files written; the export only ever runs through {@link PlanExecutor}, so it is
 * shown, confirmed, journaled and locked like every other mutation; a planning failure (unreachable
 * server, bad configuration) exits 2 because nothing has been touched yet.
 */
@Command(
    name = "export",
    mixinStandardHelpOptions = true,
    exitCodeOnInvalidInput = ExitCodes.USAGE,
    description =
        "Export repository content, or the full server, to a ZIP archive with a .jrs-upgrade.json"
            + " sidecar.")
final class ExportCommand implements Callable<Integer> {

  @Spec CommandSpec spec;
  @Mixin GlobalOptions global;

  @Option(
      names = "--uri",
      paramLabel = "<uri>",
      description = "Repository URI to export; repeatable. Default: the whole repository.")
  List<String> uris = new ArrayList<>();

  @Option(
      names = "--users-roles",
      description =
          "Include users and roles in a --uri export. A --full-server export already has them.")
  boolean usersRoles;

  @Option(names = "--access-events", description = "Include access events.")
  boolean accessEvents;

  @Option(names = "--audit-events", description = "Include audit events.")
  boolean auditEvents;

  @Option(names = "--monitoring", description = "Include monitoring events.")
  boolean monitoring;

  @Option(names = "--settings", description = "Include server settings.")
  boolean settings;

  @Option(
      names = "--full-server",
      description =
          "Export everything with the vendor tools (js-export --everything); the server keeps"
              + " running. Already carries the repository, users, roles, permissions, report jobs,"
              + " calendars and the settings changed in the UI; events only with"
              + " --access-events, --audit-events or --monitoring.")
  boolean fullServer;

  @Option(
      names = "--stop-service",
      description =
          "Stop the service while the vendor tools export, for an export taken with nothing"
              + " running, and start it again afterwards.")
  boolean stopService;

  @Option(
      names = "--strategy",
      paramLabel = "rest|vendor",
      description = "Force the REST or vendor CLI strategy instead of selecting one.")
  String strategy;

  @Option(
      names = "--key-alias",
      paramLabel = "<alias>",
      description =
          "Encrypt the archive with this key of the server's keystore instead of its own"
              + " import/export key; an import must name the same alias. The alias must exist"
              + " in the importing server's keystore too.")
  String keyAlias;

  @Option(
      names = {"--legacy-key", "--portable"},
      description =
          "Encrypt with the Legacy key ("
              + ExportRequest.PORTABLE_KEY_ALIAS
              + "), which every keystore since 7.5 holds, so another server can import the"
              + " archive; Jaspersoft's import calls it the Legacy key (the sidecar records it, so"
              + " jrs-upgrade's import needs no flag). --portable is the older name.")
  boolean portable;

  @Option(
      names = "--organization",
      paramLabel = "<id>",
      description =
          "Export one organisation only (its resources, users and roles, sub-organisations"
              + " included); every --uri is then relative to it.")
  String organization;

  @Option(
      names = "--skip-dependent-resources",
      description =
          "With --uri, do not include resources a selected resource depends on (data sources,"
              + " queries, files included by reference). The archive will cause broken"
              + " dependencies on import unless the same dependencies already exist there.")
  boolean skipDependentResources;

  @Option(
      names = "--skip-favorite-resources",
      description = "Do not export resources added to Favorites.")
  boolean skipFavoriteResources;

  @Option(
      names = "--out",
      required = true,
      paramLabel = "<file>",
      description = "Archive to write; the sidecar goes next to it.")
  Path out;

  @Option(names = "--plan", description = "Show the plan and exit without running it.")
  boolean plan;

  @Override
  public Integer call() {
    PrintWriter outWriter = spec.commandLine().getOut();
    PrintWriter err = spec.commandLine().getErr();
    Optional<ExportImportStrategy.Kind> kind;
    try {
      kind = StrategyFlag.parse(strategy);
    } catch (IllegalArgumentException e) {
      return ExitCodes.fail(outWriter, err, global.json(), ExitCodes.USAGE, e.getMessage());
    }
    if (portable && keyAlias != null) {
      return ExitCodes.fail(
          outWriter,
          err,
          global.json(),
          ExitCodes.USAGE,
          "--legacy-key (--portable) is --key-alias "
              + ExportRequest.PORTABLE_KEY_ALIAS
              + "; give one of them");
    }
    ExportImportOperations.ExportOptions options =
        new ExportImportOperations.ExportOptions(
            new LinkedHashSet<>(uris),
            usersRoles,
            accessEvents,
            auditEvents,
            monitoring,
            settings,
            fullServer,
            out,
            kind,
            stopService,
            portable
                ? Optional.of(ExportRequest.PORTABLE_KEY_ALIAS)
                : Optional.ofNullable(keyAlias),
            Optional.ofNullable(organization),
            skipDependentResources,
            skipFavoriteResources);
    try (Bootstrap boot = Bootstrap.open(global, Env.vars(), Clock.systemUTC())) {
      Services services = boot.services();
      Plan planned;
      try {
        planned = EximOps.open(services).planExport(options);
      } catch (RuntimeException e) {
        return ExitCodes.reportPlanningFailure(outWriter, err, global.json(), e);
      }
      PlanExecutor executor = new PlanExecutor(services, global, outWriter, err, Env.vars());
      return executor.execute(
          new PlanExecutor.Request(
              planned, PlanRegistry.EXPORT, PlanRegistry.exportArgs(options), plan, false));
    }
  }
}
