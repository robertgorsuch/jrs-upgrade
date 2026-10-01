package com.jaspersoft.jrsupgrade.jrs.strategy;

import com.jaspersoft.jrsupgrade.core.engine.CheckResult;
import com.jaspersoft.jrsupgrade.core.engine.Context;
import com.jaspersoft.jrsupgrade.core.engine.RetryPolicy;
import com.jaspersoft.jrsupgrade.core.engine.Step;
import com.jaspersoft.jrsupgrade.core.engine.StepResult;
import com.jaspersoft.jrsupgrade.core.event.EventSink;
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
 * Streams the finished export to {@code <output>.part} and renames it onto {@code output} (spec
 * §5.3, §7.3). Mutates only the local filesystem: re-execution overwrites the partial file and the
 * rename is atomic, so a crash never leaves a half archive under the final name; compensation
 * deletes both the partial and the final file this step produced.
 */
final class DownloadExport implements Step {

  static final String ID = "export.download";

  private final Path output;

  DownloadExport(Path output) {
    this.output = Objects.requireNonNull(output, "output");
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public String title() {
    return "Download export archive";
  }

  @Override
  public String phase() {
    return RestStrategy.EXPORT_PHASE;
  }

  @Override
  public String detail() {
    return output.toString();
  }

  @Override
  public RetryPolicy retryPolicy() {
    return RetryPolicy.HTTP_DEFAULT;
  }

  @Override
  public CheckResult precheck(Context ctx) {
    Path handleFile = RunFiles.in(ctx, RunFiles.EXPORT_HANDLE);
    try {
      if (RunFiles.read(handleFile).isEmpty()) {
        return CheckResult.fail(
            "no export task handle recorded in " + handleFile, "start the export again");
      }
    } catch (IOException e) {
      return CheckResult.fail(
          "cannot read " + handleFile + ": " + e.getMessage(), "check the run directory");
    }
    return CheckResult.pass();
  }

  @Override
  public StepResult execute(Context ctx, EventSink out) {
    Path part = RunFiles.partOf(output);
    String id;
    try {
      id = RunFiles.read(RunFiles.in(ctx, RunFiles.EXPORT_HANDLE)).orElseThrow();
      Path parent = output.toAbsolutePath().getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
    } catch (IOException e) {
      return Failures.recoverable(
          "cannot prepare " + output + ": " + e.getMessage(),
          List.of(output),
          "check that the output directory is writable");
    }
    JrsAdapter adapter = ctx.service(JrsAdapter.class);
    try {
      adapter.downloadExport(new Handles.ExportHandle(id), part, ctx.cancel()::isCancelled);
    } catch (JrsUnreachableException e) {
      return Failures.retryable(
          "server unreachable while downloading: " + e.getMessage(),
          List.of(e.url()),
          e.remediation());
    } catch (RestException e) {
      if (!e.transientFailure()) {
        throw e;
      }
      return Failures.transientHttp(
          e, "cannot download the export", Failures.TRANSIENT_REMEDIATION);
    }
    try {
      long size = Files.exists(part) ? Files.size(part) : 0L;
      if (size <= 0) {
        Files.deleteIfExists(part);
        return Failures.recoverable(
            "export " + id + " downloaded as an empty file",
            List.of(part),
            "check the export task on the server and run the export again");
      }
      RunFiles.replace(part, output);
      Logs.info(out, ctx, this, "downloaded " + size + " bytes to " + output);
      return StepResult.ok();
    } catch (IOException e) {
      return Failures.recoverable(
          "cannot finish writing " + output + ": " + e.getMessage(),
          List.of(part, output),
          "check disk space and permissions on " + output.toAbsolutePath().getParent());
    }
  }

  @Override
  public CheckResult postcheck(Context ctx) {
    try {
      if (!Files.isRegularFile(output) || Files.size(output) <= 0) {
        return CheckResult.fail(
            output + " is missing or empty after download", "run the export again");
      }
    } catch (IOException e) {
      return CheckResult.fail("cannot inspect " + output + ": " + e.getMessage(), "check the path");
    }
    Optional<String> empty = ExportArchives.problem(output);
    if (empty.isPresent()) {
      return CheckResult.fail(empty.get(), "check the --uri values against the repository");
    }
    return CheckResult.pass();
  }

  @Override
  public StepResult compensate(Context ctx, EventSink out) {
    Path part = RunFiles.partOf(output);
    try {
      RunFiles.delete(part);
      RunFiles.delete(output);
      return StepResult.ok();
    } catch (IOException e) {
      return Failures.recoverable(
          "cannot delete " + output + ": " + e.getMessage(),
          List.of(part, output),
          "delete the file by hand");
    }
  }
}
