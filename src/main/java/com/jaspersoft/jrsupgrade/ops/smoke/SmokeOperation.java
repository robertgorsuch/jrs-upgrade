package com.jaspersoft.jrsupgrade.ops.smoke;

import com.jaspersoft.jrsupgrade.core.config.ConfigException;
import com.jaspersoft.jrsupgrade.core.engine.CancellationToken;
import com.jaspersoft.jrsupgrade.core.engine.Context;
import com.jaspersoft.jrsupgrade.core.engine.Plan;
import com.jaspersoft.jrsupgrade.core.engine.RunIds;
import com.jaspersoft.jrsupgrade.core.engine.RunOptions;
import com.jaspersoft.jrsupgrade.core.engine.RunOutcome;
import com.jaspersoft.jrsupgrade.core.engine.Runner;
import com.jaspersoft.jrsupgrade.core.engine.Sleeper;
import com.jaspersoft.jrsupgrade.core.event.EventSink;
import com.jaspersoft.jrsupgrade.core.secrets.Secret;
import com.jaspersoft.jrsupgrade.core.secrets.SecretException;
import com.jaspersoft.jrsupgrade.core.secrets.SecretRef;
import com.jaspersoft.jrsupgrade.jrs.api.Credentials;
import com.jaspersoft.jrsupgrade.jrs.api.ExportRequest;
import com.jaspersoft.jrsupgrade.jrs.api.Handles;
import com.jaspersoft.jrsupgrade.jrs.api.JrsAdapter;
import com.jaspersoft.jrsupgrade.jrs.api.JrsUnreachableException;
import com.jaspersoft.jrsupgrade.jrs.api.ServerIdentity;
import com.jaspersoft.jrsupgrade.jrs.api.Session;
import com.jaspersoft.jrsupgrade.ops.ReportItem;
import com.jaspersoft.jrsupgrade.ops.Services;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code jrs-upgrade smoke} (spec §12.2). Invariants: without {@code --mutating} every request is
 * read-only (login, serverInfo, folder listing, report run, scheduler query, export round trip);
 * temporary files live under {@code $JRS_UPGRADE_HOME/runs} and are removed afterwards; the only
 * mutation, {@code --mutating}, is a {@link Plan} executed by the engine's {@link Runner} under the
 * run lock so it is journaled and compensated like every other mutation; a missing sample report is
 * a WARN, never a FAIL.
 */
public final class SmokeOperation {

  /** The operation id a {@code smoke --mutating} run is journaled under. */
  public static final String MUTATING_OPERATION = "smoke --mutating";

  public static final String DEFAULT_REPORT_URI = "/public/Samples/Reports/AllAccounts";
  public static final String EXPORT_ROOT = "/public";
  static final Duration EXPORT_TIMEOUT = Duration.ofSeconds(60);
  static final Duration POLL_INTERVAL = Duration.ofSeconds(1);

  private static final Logger LOG = LoggerFactory.getLogger(SmokeOperation.class);

  private final Services services;
  private final EventSink events;
  private final Sleeper sleeper;

  public SmokeOperation(Services services) {
    this(services, EventSink.discard(), Sleeper.system());
  }

  public SmokeOperation(Services services, EventSink events, Sleeper sleeper) {
    this.services = Objects.requireNonNull(services, "services");
    this.events = Objects.requireNonNull(events, "events");
    this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
  }

  public SmokeReport run(SmokeOptions options) {
    return run(options, new CancellationToken());
  }

  public SmokeReport run(SmokeOptions options, CancellationToken cancel) {
    Objects.requireNonNull(options, "options");
    Objects.requireNonNull(cancel, "cancel");
    List<ReportItem> items = new ArrayList<>();
    JrsAdapter adapter;
    ServerIdentity identity;
    try {
      adapter = services.adapter().get();
      identity = adapter.identity();
      items.add(
          ReportItem.pass(
              "server",
              identity.version() + " " + identity.edition() + " at " + identity.baseUrl()));
    } catch (JrsUnreachableException e) {
      items.add(ReportItem.fail("server", e.getMessage(), e.remediation()));
      skipRest(items, options);
      return SmokeReport.of(items, Optional.empty());
    } catch (ConfigException e) {
      items.add(ReportItem.fail("server", e.getMessage(), e.remediation()));
      skipRest(items, options);
      return SmokeReport.of(items, Optional.empty());
    }
    items.add(login(adapter));
    items.add(repository(adapter));
    items.add(report(adapter));
    items.add(scheduler(adapter));
    items.add(export(adapter, cancel));
    Optional<String> runId = Optional.empty();
    if (options.mutating()) {
      String id = RunIds.next(services.clock());
      runId = Optional.of(id);
      items.add(mutating(adapter, identity, id, cancel));
    }
    return SmokeReport.of(items, runId);
  }

  private static void skipRest(List<ReportItem> items, SmokeOptions options) {
    for (String name : List.of("login", "repository", "report", "scheduler", "export")) {
      items.add(ReportItem.skip(name, "server unreachable", "fix the server check first"));
    }
    if (options.mutating()) {
      items.add(ReportItem.skip("mutating", "server unreachable", "fix the server check first"));
    }
  }

  private ReportItem login(JrsAdapter adapter) {
    Optional<String> username = services.config().server().auth().username();
    Optional<SecretRef> ref = services.config().server().auth().passwordRef();
    if (username.isEmpty() || ref.isEmpty()) {
      return ReportItem.fail(
          "login",
          "server.auth.username or server.auth.passwordRef is not configured",
          "set both in config.yaml (run jrs-upgrade init)");
    }
    try (Secret password = services.secrets().resolve(ref.get())) {
      Session session = adapter.login(new Credentials(username.get(), password, Optional.empty()));
      return ReportItem.pass("login", username.get() + " via " + session.mode());
    } catch (SecretException e) {
      return ReportItem.fail("login", e.getMessage(), "fix " + ref.get().render());
    } catch (RuntimeException e) {
      return ReportItem.fail(
          "login",
          "login as " + username.get() + " failed: " + e.getMessage(),
          "check the credentials behind " + ref.get().render());
    }
  }

  private static ReportItem repository(JrsAdapter adapter) {
    try {
      List<String> children = adapter.listFolder("/");
      return ReportItem.pass("repository", "/ has " + children.size() + " entries");
    } catch (RuntimeException e) {
      return ReportItem.fail(
          "repository", "cannot list /: " + e.getMessage(), "check the login's repository rights");
    }
  }

  private ReportItem report(JrsAdapter adapter) {
    String uri = services.config().smoke().reportUri().orElse(DEFAULT_REPORT_URI);
    Path target;
    try {
      target = tempFile("smoke-report", ".pdf");
    } catch (IOException e) {
      return ReportItem.fail(
          "report",
          "cannot create a temp file: " + e.getMessage(),
          "check " + services.home().runs());
    }
    try {
      adapter.runReportToPdf(uri, target);
      Optional<String> problem = PdfCheck.problem(target);
      return problem
          .map(
              p ->
                  ReportItem.fail(
                      "report", uri + ": " + p, "check the server log for the report run"))
          .orElseGet(
              () ->
                  ReportItem.pass("report", uri + " rendered to PDF (" + size(target) + " bytes)"));
    } catch (IOException | RuntimeException e) {
      return ReportItem.warn(
          "report",
          uri + " could not be run: " + e.getMessage(),
          "set smoke.reportUri to a report that exists on this server");
    } finally {
      deleteQuietly(target);
    }
  }

  private static ReportItem scheduler(JrsAdapter adapter) {
    try {
      return adapter.schedulerReachable()
          ? ReportItem.pass("scheduler", "GET /rest_v2/jobs answers")
          : ReportItem.fail(
              "scheduler",
              "GET /rest_v2/jobs does not answer",
              "check the scheduler (Quartz) in the server log");
    } catch (RuntimeException e) {
      return ReportItem.fail(
          "scheduler", "scheduler query failed: " + e.getMessage(), "check the server log");
    }
  }

  /**
   * Polls the export with the caller's token (review finding 1.11): a cancellation ends the probe
   * within one sleep slice and propagates, so an upgrade's verify smoke ends the run instead of
   * reporting a failed export after the poll interval.
   */
  private ReportItem export(JrsAdapter adapter, CancellationToken cancel) {
    Path target;
    try {
      target = tempFile("smoke-export", ".zip");
    } catch (IOException e) {
      return ReportItem.fail(
          "export",
          "cannot create a temp file: " + e.getMessage(),
          "check " + services.home().runs());
    }
    try {
      ExportRequest request =
          new ExportRequest(
              ExportRequest.Scope.REPOSITORY,
              Set.of(EXPORT_ROOT),
              false,
              false,
              false,
              false,
              false,
              false,
              target);
      Handles.ExportHandle handle = adapter.startExport(request);
      Instant deadline = services.clock().instant().plus(EXPORT_TIMEOUT);
      Handles.ExportStatus status = adapter.pollExport(handle);
      int polls = 1;
      while (!status.done()) {
        if (!services.clock().instant().isBefore(deadline)
            || polls >= EXPORT_TIMEOUT.dividedBy(POLL_INTERVAL)) {
          return ReportItem.fail(
              "export",
              "export " + handle.id() + " not finished after " + EXPORT_TIMEOUT.toSeconds() + " s",
              "check the server's export queue and log");
        }
        sleeper.sleep(POLL_INTERVAL, cancel);
        status = adapter.pollExport(handle);
        polls++;
      }
      if (status.phase() == Handles.Phase.FAILED) {
        return ReportItem.fail(
            "export",
            "export of " + EXPORT_ROOT + " failed: " + status.message().orElse("no message"),
            "check the server log for the export");
      }
      Path downloaded = adapter.downloadExport(handle, target);
      long size = size(downloaded);
      return size > 0
          ? ReportItem.pass("export", EXPORT_ROOT + " exported and downloaded (" + size + " bytes)")
          : ReportItem.fail(
              "export", "downloaded export is empty", "check the server log for the export");
    } catch (CancellationToken.CancelledException e) {
      throw e;
    } catch (RuntimeException e) {
      return ReportItem.fail(
          "export",
          "export round trip failed: " + e.getMessage(),
          "check the server log for the export");
    } finally {
      deleteQuietly(target);
    }
  }

  private ReportItem mutating(
      JrsAdapter adapter, ServerIdentity identity, String runId, CancellationToken cancel) {
    Plan plan = SmokePlan.build(runId, identity);
    Context ctx =
        new Context(
            runId, services.home(), services.platform(), cancel, Map.of(JrsAdapter.class, adapter));
    Runner runner = new Runner(services.stateStore().get(), events, services.clock(), sleeper);
    RunOutcome outcome = runner.run(plan, ctx, plan.fingerprint(), RunOptions.DEFAULT);
    String folder = SmokePlan.folderUri(runId);
    return switch (outcome) {
      case RunOutcome.Succeeded s ->
          ReportItem.pass(
              "mutating", "created, ran and removed " + folder + " (run " + runId + ")");
      case RunOutcome.RolledBack r ->
          ReportItem.fail(
              "mutating",
              r.cause() + " (rolled back, run " + runId + ")",
              "check the server log; nothing was left under " + folder);
      case RunOutcome.Failed f ->
          ReportItem.fail(
              "mutating",
              f.cause() + (f.rollbackIncomplete() ? " (rollback incomplete)" : ""),
              f.nextAction());
      case RunOutcome.Cancelled c ->
          ReportItem.fail(
              "mutating", "cancelled: " + c.reason(), "run jrs-upgrade runs recover " + runId);
      case RunOutcome.PrecheckFailed p ->
          ReportItem.fail("mutating", p.stepId() + ": " + p.message(), p.remediation());
      case RunOutcome.FingerprintMismatch m ->
          ReportItem.fail(
              "mutating", "fingerprint changed: " + m.changedKeys(), "re-run smoke --mutating");
    };
  }

  private Path tempFile(String prefix, String suffix) throws IOException {
    Path dir = services.home().runs();
    Files.createDirectories(dir);
    return Files.createTempFile(dir, prefix, suffix);
  }

  private static long size(Path file) {
    try {
      return Files.size(file);
    } catch (IOException e) {
      return 0;
    }
  }

  private static void deleteQuietly(Path file) {
    try {
      Files.deleteIfExists(file);
    } catch (IOException e) {
      LOG.debug("cannot delete {}", file, e);
    }
  }
}
