package com.jaspersoft.jrsupgrade.jrs.strategy;

import com.jaspersoft.jrsupgrade.core.engine.CheckResult;
import com.jaspersoft.jrsupgrade.core.engine.Context;
import com.jaspersoft.jrsupgrade.core.engine.Step;
import com.jaspersoft.jrsupgrade.core.engine.StepResult;
import com.jaspersoft.jrsupgrade.core.event.EventSink;
import com.jaspersoft.jrsupgrade.jrs.api.Handles;
import com.jaspersoft.jrsupgrade.jrs.api.JrsAdapter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Polls {@code GET /rest_v2/export/{id}/state} until the task is ready (spec §7.3). Non-mutating; a
 * {@code FAILED} phase becomes a {@code Recoverable} failure carrying the server's message, so the
 * download step never runs; a cancellation checkpoint precedes every poll.
 */
final class PollExport implements Step {

  static final String ID = "export.poll";

  private final Polling polling;

  PollExport(Polling polling) {
    this.polling = Objects.requireNonNull(polling, "polling");
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public String title() {
    return "Wait for export task";
  }

  @Override
  public String phase() {
    return RestStrategy.EXPORT_PHASE;
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
    Path handleFile = RunFiles.in(ctx, RunFiles.EXPORT_HANDLE);
    try {
      if (RunFiles.read(handleFile).isEmpty()) {
        return CheckResult.fail(
            "no export task handle recorded in " + handleFile,
            "start the export again; the start step records the task id before polling");
      }
    } catch (IOException e) {
      return CheckResult.fail(
          "cannot read " + handleFile + ": " + e.getMessage(), "check the run directory");
    }
    return CheckResult.pass();
  }

  @Override
  public StepResult execute(Context ctx, EventSink out) {
    Path handleFile = RunFiles.in(ctx, RunFiles.EXPORT_HANDLE);
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
    Handles.ExportHandle handle = new Handles.ExportHandle(id);
    AtomicReference<Optional<String>> lastMessage = new AtomicReference<>(Optional.empty());
    Polling.Outcome outcome =
        polling.until(
            ctx,
            out,
            this,
            "export " + id,
            () -> {
              Handles.ExportStatus s = adapter.pollExport(handle);
              lastMessage.set(s.message());
              return switch (s.phase()) {
                case INPROGRESS -> new Polling.Tick.Continue(s.message().orElse(""));
                case READY -> new Polling.Tick.Done();
                  // exports never pend (REST 10.1 p.113); a server that says so is not one the
                  // poll can wait for
                case FAILED, PENDING ->
                    new Polling.Tick.Failed(
                        s.message().orElse("export failed without a message")
                            + s.errorCode().map(c -> " (" + c + ")").orElse(""));
              };
            });
    return switch (outcome) {
      case Polling.Outcome.Completed c -> {
        Logs.info(
            out,
            ctx,
            this,
            "export "
                + id
                + " ready after "
                + c.attempts()
                + " poll(s)"
                + lastMessage.get().map(m -> ": " + m).orElse(""));
        yield StepResult.ok();
      }
      case Polling.Outcome.Failed f ->
          Failures.recoverable(
              "server reported export " + id + " failed: " + f.message(),
              List.of(),
              "check the jasperserver log for the export task, fix the cause and export again");
      case Polling.Outcome.TimedOut t ->
          Failures.recoverable(
              "export " + id + " still in progress after " + t.after().toMinutes() + " minutes",
              List.of(),
              "wait for the task to finish on the server or narrow the export scope, then run"
                  + " again");
    };
  }

  @Override
  public StepResult compensate(Context ctx, EventSink out) {
    return StepResult.ok();
  }
}
