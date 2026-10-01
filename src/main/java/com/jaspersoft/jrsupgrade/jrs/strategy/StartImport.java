package com.jaspersoft.jrsupgrade.jrs.strategy;

import com.jaspersoft.jrsupgrade.core.engine.CheckResult;
import com.jaspersoft.jrsupgrade.core.engine.Context;
import com.jaspersoft.jrsupgrade.core.engine.RetryPolicy;
import com.jaspersoft.jrsupgrade.core.engine.Step;
import com.jaspersoft.jrsupgrade.core.engine.StepResult;
import com.jaspersoft.jrsupgrade.core.event.EventSink;
import com.jaspersoft.jrsupgrade.jrs.api.Handles;
import com.jaspersoft.jrsupgrade.jrs.api.ImportRequest;
import com.jaspersoft.jrsupgrade.jrs.api.JrsAdapter;
import com.jaspersoft.jrsupgrade.jrs.api.JrsUnreachableException;
import com.jaspersoft.jrsupgrade.jrs.rest.RestException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * {@code POST /rest_v2/import} streaming the archive (spec §7.3, §9.4). Repository-mutating and
 * idempotent through the run-scoped {@code import-handle.txt}: a recorded task id is reused instead
 * of uploading again, and an {@code import-started.txt} without a handle means an earlier attempt
 * may have started an import, which is fatal rather than retried (ADR-0017). Compensation is
 * intentionally a no-op that only logs: the repository is restored by the ops layer's rollback
 * steps of this phase, which delete what the import created (folders that did not exist before it,
 * issue #139, and additions under folders that did, issue #100) and re-import the snapshot taken
 * before this phase (spec §9.4); this step cannot undo a server-side import itself.
 */
final class StartImport implements Step {

  static final String ID = "import.start";
  static final String ROLLBACK_NOTE =
      "repository rollback is handled by the import phase's rollback steps: deleting what the"
          + " import created and re-importing the pre-import snapshot";

  /** Statuses that mean the server did not accept the request, so no import can have started. */
  private static final Set<Integer> NOT_ACCEPTED = Set.of(408, 429, 503);

  static final String AMBIGUOUS_START =
      "the server may have accepted the import before its answer was lost; jrs-upgrade does not"
          + " upload the archive a second time (issue #44)";
  static final String AMBIGUOUS_NEXT_ACTION =
      "check the server log and the repository for an import started at this time, let it"
          + " finish, verify the repository, and re-import the archive by hand only if it did"
          + " not run";

  private final ImportRequest request;

  StartImport(ImportRequest request) {
    this.request = Objects.requireNonNull(request, "request");
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public String title() {
    return "Start import task";
  }

  @Override
  public String phase() {
    return RestStrategy.IMPORT_PHASE;
  }

  @Override
  public String detail() {
    return request.archive() + (request.update() ? " (update)" : "");
  }

  @Override
  public RetryPolicy retryPolicy() {
    return RetryPolicy.HTTP_DEFAULT;
  }

  @Override
  public CheckResult precheck(Context ctx) {
    Path archive = request.archive();
    try {
      if (!Files.isRegularFile(archive) || Files.size(archive) <= 0) {
        return CheckResult.fail(
            "archive " + archive + " is missing or empty", "pass an export archive to import");
      }
    } catch (IOException e) {
      return CheckResult.fail("cannot inspect " + archive + ": " + e.getMessage(), "check path");
    }
    return CheckResult.pass();
  }

  @Override
  public StepResult execute(Context ctx, EventSink out) {
    Path handleFile = RunFiles.in(ctx, RunFiles.IMPORT_HANDLE);
    Path startedFile = RunFiles.in(ctx, RunFiles.IMPORT_STARTED);
    try {
      Optional<String> existing = RunFiles.read(handleFile);
      if (existing.isPresent()) {
        Logs.info(out, ctx, this, "reusing import task " + existing.get());
        return StepResult.ok();
      }
      if (Files.exists(startedFile)) {
        return Failures.fatal(AMBIGUOUS_START, AMBIGUOUS_NEXT_ACTION);
      }
      RunFiles.write(startedFile, request.archive().toString());
      Handles.ImportHandle handle;
      try {
        handle =
            ctx.service(JrsAdapter.class)
                .startImport(request, request.archive(), ctx.cancel()::isCancelled);
      } catch (JrsUnreachableException e) {
        return Failures.fatal(
            "server unreachable while starting the import ("
                + e.getMessage()
                + "); "
                + AMBIGUOUS_START,
            AMBIGUOUS_NEXT_ACTION);
      } catch (RestException e) {
        if (NOT_ACCEPTED.contains(e.status())) {
          RunFiles.delete(startedFile);
          return Failures.transientHttp(
              e, "cannot start the import", Failures.TRANSIENT_REMEDIATION);
        }
        if (e.transientFailure()) {
          return Failures.fatal(
              "cannot start the import: HTTP "
                  + e.status()
                  + " ("
                  + e.getMessage()
                  + "); "
                  + AMBIGUOUS_START,
              AMBIGUOUS_NEXT_ACTION);
        }
        RunFiles.delete(startedFile);
        throw e;
      }
      RunFiles.write(handleFile, handle.id());
      Logs.info(out, ctx, this, "import task " + handle.id() + " started");
      return StepResult.ok();
    } catch (IOException e) {
      return Failures.recoverable(
          "cannot record the import task state: " + e.getMessage(),
          List.of(handleFile, startedFile),
          "check that " + handleFile.getParent() + " is writable");
    }
  }

  @Override
  public StepResult compensate(Context ctx, EventSink out) {
    Logs.info(out, ctx, this, ROLLBACK_NOTE);
    return StepResult.ok();
  }
}
