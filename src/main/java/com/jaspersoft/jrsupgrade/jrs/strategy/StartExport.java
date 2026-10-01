package com.jaspersoft.jrsupgrade.jrs.strategy;

import com.jaspersoft.jrsupgrade.core.engine.CheckResult;
import com.jaspersoft.jrsupgrade.core.engine.Context;
import com.jaspersoft.jrsupgrade.core.engine.RetryPolicy;
import com.jaspersoft.jrsupgrade.core.engine.Step;
import com.jaspersoft.jrsupgrade.core.engine.StepResult;
import com.jaspersoft.jrsupgrade.core.event.EventSink;
import com.jaspersoft.jrsupgrade.jrs.api.ExportRequest;
import com.jaspersoft.jrsupgrade.jrs.api.Handles;
import com.jaspersoft.jrsupgrade.jrs.api.JrsAdapter;
import com.jaspersoft.jrsupgrade.jrs.api.JrsUnreachableException;
import com.jaspersoft.jrsupgrade.jrs.rest.RestException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code POST /rest_v2/export} (spec §7.3). Idempotent through the run-scoped {@code
 * export-handle.txt}: once a task id is recorded, re-execution reuses it instead of starting a
 * second export, so a crash between start and poll converges. Read-only for the repository, hence
 * non-mutating; the only window where a retry could start a second task is between the server's
 * answer and the handle write, which is accepted and documented here.
 */
final class StartExport implements Step {

  static final String ID = "export.start";

  private final ExportRequest request;

  StartExport(ExportRequest request) {
    this.request = Objects.requireNonNull(request, "request");
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public String title() {
    return "Start export task";
  }

  @Override
  public String phase() {
    return RestStrategy.EXPORT_PHASE;
  }

  @Override
  public String detail() {
    return request.fullServer() || request.scope() == ExportRequest.Scope.EVERYTHING
        ? "everything"
        : "uris " + String.join(", ", request.uris());
  }

  @Override
  public boolean mutating() {
    return false;
  }

  @Override
  public RetryPolicy retryPolicy() {
    return RetryPolicy.HTTP_DEFAULT;
  }

  @Override
  public CheckResult precheck(Context ctx) {
    Path parent = request.output().toAbsolutePath().getParent();
    if (parent != null && Files.exists(parent) && !Files.isDirectory(parent)) {
      return CheckResult.fail(
          parent + " is not a directory", "choose an output path inside a writable directory");
    }
    if (Files.exists(request.output())) {
      return CheckResult.warn(request.output() + " exists and will be replaced");
    }
    return CheckResult.pass();
  }

  @Override
  public StepResult execute(Context ctx, EventSink out) {
    Path handleFile = RunFiles.in(ctx, RunFiles.EXPORT_HANDLE);
    try {
      Optional<String> existing = RunFiles.read(handleFile);
      if (existing.isPresent()) {
        Logs.info(out, ctx, this, "reusing export task " + existing.get());
        return StepResult.ok();
      }
      JrsAdapter adapter = ctx.service(JrsAdapter.class);
      Handles.ExportHandle handle;
      try {
        handle = adapter.startExport(request);
      } catch (JrsUnreachableException e) {
        return Failures.retryable(
            "server unreachable: " + e.getMessage(), List.of(e.url()), e.remediation());
      } catch (RestException e) {
        if (!e.transientFailure()) {
          throw e;
        }
        return Failures.transientHttp(e, "cannot start the export", Failures.TRANSIENT_REMEDIATION);
      }
      RunFiles.write(handleFile, handle.id());
      Logs.info(out, ctx, this, "export task " + handle.id() + " started");
      return StepResult.ok();
    } catch (IOException e) {
      return Failures.recoverable(
          "cannot record the export task handle: " + e.getMessage(),
          List.of(handleFile),
          "check that " + handleFile.getParent() + " is writable");
    }
  }

  @Override
  public StepResult compensate(Context ctx, EventSink out) {
    return StepResult.ok();
  }
}
