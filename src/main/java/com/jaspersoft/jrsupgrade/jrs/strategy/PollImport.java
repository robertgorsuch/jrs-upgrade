package com.jaspersoft.jrsupgrade.jrs.strategy;

import com.jaspersoft.jrsupgrade.core.engine.CheckResult;
import com.jaspersoft.jrsupgrade.core.engine.Context;
import com.jaspersoft.jrsupgrade.core.engine.Step;
import com.jaspersoft.jrsupgrade.core.engine.StepResult;
import com.jaspersoft.jrsupgrade.core.event.EventSink;
import com.jaspersoft.jrsupgrade.jrs.api.Handles;
import com.jaspersoft.jrsupgrade.jrs.api.JrsAdapter;
import com.jaspersoft.jrsupgrade.jrs.rest.RestException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Polls {@code GET /rest_v2/import/{id}/state} until the task finishes (spec §7.3). Non-mutating; a
 * {@code FAILED} phase becomes a {@code Recoverable} failure with the server's message, which makes
 * the Runner compensate the phase (i.e. re-import the pre-import snapshot). A {@code PENDING} phase
 * (REST reference 10.1 p.119: broken dependencies or an organisation mismatch, found before
 * anything was imported) never resumes by itself, so the step cancels the task and fails {@code
 * Recoverable} naming the code, the resources and the flag that gets past it; the snapshot
 * re-import that follows puts back what is already there. A task that outlives the poll, or a
 * definitive HTTP error while asking about it, is {@code Fatal} instead: the task may still be
 * running, and a re-import on top of it would race it (assessment item U3).
 */
final class PollImport implements Step {

  static final String ID = "import.poll";

  private final Polling polling;

  PollImport(Polling polling) {
    this.polling = Objects.requireNonNull(polling, "polling");
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public String title() {
    return "Wait for import task";
  }

  @Override
  public String phase() {
    return RestStrategy.IMPORT_PHASE;
  }

  @Override
  public String detail() {
    return "timeout " + polling.timeout().toMinutes() + "m";
  }

  @Override
  public boolean mutating() {
    return false;
  }

  @Override
  public CheckResult precheck(Context ctx) {
    Path handleFile = RunFiles.in(ctx, RunFiles.IMPORT_HANDLE);
    try {
      if (RunFiles.read(handleFile).isEmpty()) {
        return CheckResult.fail(
            "no import task handle recorded in " + handleFile, "start the import again");
      }
    } catch (IOException e) {
      return CheckResult.fail(
          "cannot read " + handleFile + ": " + e.getMessage(), "check the run directory");
    }
    return CheckResult.pass();
  }

  @Override
  public StepResult execute(Context ctx, EventSink out) {
    Path handleFile = RunFiles.in(ctx, RunFiles.IMPORT_HANDLE);
    String id;
    try {
      id = RunFiles.read(handleFile).orElseThrow();
    } catch (IOException e) {
      return Failures.recoverable(
          "cannot read " + handleFile + ": " + e.getMessage(),
          List.of(handleFile),
          "check the run directory");
    }
    JrsAdapter adapter = ctx.service(JrsAdapter.class);
    Handles.ImportHandle handle = new Handles.ImportHandle(id);
    AtomicReference<Handles.ImportStatus> pending = new AtomicReference<>();
    Polling.Outcome outcome;
    try {
      outcome =
          polling.until(
              ctx,
              out,
              this,
              "import " + id,
              () -> {
                Handles.ImportStatus s = adapter.pollImport(handle);
                return switch (s.phase()) {
                  case INPROGRESS -> new Polling.Tick.Continue(s.message().orElse(""));
                  case READY -> new Polling.Tick.Done();
                  case PENDING -> {
                    pending.set(s);
                    yield new Polling.Tick.Done();
                  }
                  case FAILED ->
                      new Polling.Tick.Failed(
                          s.message().orElse("import failed without a message")
                              + s.errorCode().map(c -> " (" + c + ")").orElse(""));
                };
              });
    } catch (RestException e) {
      // A definitive HTTP error while asking about a task that may still be running: the runner
      // must not compensate, because the pre-import snapshot would be imported on top of an
      // import still in flight (assessment item U3).
      return Failures.fatal(
          "cannot tell whether import "
              + id
              + " is still running: HTTP "
              + e.status()
              + " ("
              + e.getMessage()
              + ")",
          "check the import task on the server and verify the repository; nothing is re-imported"
              + " while the task may still be running");
    }
    if (pending.get() != null) {
      return pending(ctx, out, adapter, handle, pending.get());
    }
    return switch (outcome) {
      case Polling.Outcome.Completed c -> {
        Logs.info(out, ctx, this, "import " + id + " ready after " + c.attempts() + " poll(s)");
        yield StepResult.ok();
      }
      case Polling.Outcome.Failed f ->
          Failures.recoverable(
              "server reported import " + id + " failed: " + f.message(),
              List.of(),
              "check the jasperserver log for the import task; the pre-import snapshot is"
                  + " re-imported by rollback");
        // Fatal, not recoverable: the task is still running on the server, and compensating the
        // import phase would start a second import against it (assessment item U3).
      case Polling.Outcome.TimedOut t ->
          Failures.fatal(
              "import " + id + " still in progress after " + t.after().toMinutes() + " minutes",
              "wait for the task to finish on the server, then verify the repository; the"
                  + " pre-import snapshot is not re-imported while the task may still be running");
    };
  }

  /**
   * The server stopped before importing anything and will hold the task until it is restarted with
   * other options or cancelled (REST 10.1 pp.119-124). jrs-upgrade cancels it: the run's answer to
   * the operator is the failure below, and the next run uploads the catalog again with the option
   * it names.
   */
  private StepResult pending(
      Context ctx,
      EventSink out,
      JrsAdapter adapter,
      Handles.ImportHandle handle,
      Handles.ImportStatus status) {
    String code = status.errorCode().orElse("no error code");
    String cause =
        "server holds import "
            + handle.id()
            + " pending ("
            + code
            + (status.errorParameters().isEmpty()
                ? ""
                : ": " + String.join(", ", status.errorParameters()))
            + "); nothing was imported";
    String nextAction =
        switch (code) {
          case "import.broken.dependencies" ->
              "run again with --broken-dependencies skip (leave those resources out) or"
                  + " --broken-dependencies include (import them with the dependency missing)";
          case "import.organizations.not.match" ->
              "the catalog was exported from a different organisation than the target; import it"
                  + " with the vendor tools' --organization or --merge-organization options";
          default -> "see the server's import task log, then run again";
        };
    try {
      adapter.cancelImport(handle);
      Logs.info(out, ctx, this, "cancelled pending import " + handle.id());
    } catch (RestException e) {
      Logs.warn(
          out,
          ctx,
          this,
          "cannot cancel pending import "
              + handle.id()
              + " (HTTP "
              + e.status()
              + "); delete it from the server's import page");
    }
    return Failures.recoverable(cause, List.of(), nextAction);
  }

  @Override
  public StepResult compensate(Context ctx, EventSink out) {
    return StepResult.ok();
  }
}
