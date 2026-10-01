package com.jaspersoft.jrsupgrade.jrs.strategy;

import com.jaspersoft.jrsupgrade.core.engine.CheckResult;
import com.jaspersoft.jrsupgrade.core.engine.Context;
import com.jaspersoft.jrsupgrade.core.engine.Step;
import com.jaspersoft.jrsupgrade.core.engine.StepResult;
import com.jaspersoft.jrsupgrade.core.event.EventSink;
import com.jaspersoft.jrsupgrade.jrs.api.ExportImportStrategy;
import com.jaspersoft.jrsupgrade.jrs.api.ExportRequest;
import com.jaspersoft.jrsupgrade.jrs.api.JrsAdapter;
import com.jaspersoft.jrsupgrade.jrs.api.JrsUnreachableException;
import com.jaspersoft.jrsupgrade.jrs.api.KeystoreInfo;
import com.jaspersoft.jrsupgrade.jrs.api.ServerIdentity;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Objects;

/**
 * Writes {@code <output>.jrs-upgrade.json} next to the archive (spec §9.3) with the server
 * identity, the keystore fingerprint, the request flags and the streaming SHA-256 of the archive.
 * Mutates only the local filesystem; re-execution rewrites the same file; compensation deletes it.
 * Shared by the REST and vendor strategies, so it runs after the server is back up in the vendor
 * case.
 */
final class WriteSidecar implements Step {

  static final String ID = "export.sidecar";

  private final String phase;
  private final ExportRequest request;
  private final ExportImportStrategy.Kind strategy;
  private final Clock clock;

  WriteSidecar(
      String phase, ExportRequest request, ExportImportStrategy.Kind strategy, Clock clock) {
    this.phase = Objects.requireNonNull(phase, "phase");
    this.request = Objects.requireNonNull(request, "request");
    this.strategy = Objects.requireNonNull(strategy, "strategy");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  Path file() {
    return Sidecar.pathFor(request.output());
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public String title() {
    return "Write export sidecar";
  }

  @Override
  public String phase() {
    return phase;
  }

  @Override
  public String detail() {
    return file().toString();
  }

  @Override
  public CheckResult precheck(Context ctx) {
    return CheckResult.pass();
  }

  @Override
  public StepResult execute(Context ctx, EventSink out) {
    Path archive = request.output();
    Path target = file();
    if (!Files.isRegularFile(archive)) {
      return Failures.recoverable(
          "archive " + archive + " does not exist", List.of(archive), "run the export again");
    }
    JrsAdapter adapter = ctx.service(JrsAdapter.class);
    ServerIdentity identity;
    KeystoreInfo keystore;
    try {
      identity = adapter.identity();
      keystore = adapter.keystore();
    } catch (JrsUnreachableException e) {
      return Failures.retryable(
          "server unreachable: " + e.getMessage(), List.of(e.url()), e.remediation());
    }
    try {
      String sha256 = ctx.platform().files().sha256(archive);
      Sidecar sidecar =
          new Sidecar(
              clock.instant(),
              identity.fingerprintInput(),
              identity.version(),
              keystore.fingerprint(),
              Sidecar.Flags.of(request),
              sha256,
              strategy);
      Sidecar.write(target, sidecar);
      Logs.info(
          out,
          ctx,
          this,
          "sidecar written to "
              + target
              + (keystore.fingerprint().isPresent()
                  ? " with keystore fingerprint"
                  : " without keystore fingerprint (" + keystore.reason().orElse("") + ")"));
      return StepResult.ok();
    } catch (IOException e) {
      return Failures.recoverable(
          "cannot write " + target + ": " + e.getMessage(),
          List.of(target),
          "check permissions next to " + archive);
    }
  }

  @Override
  public CheckResult postcheck(Context ctx) {
    return Files.isRegularFile(file())
        ? CheckResult.pass()
        : CheckResult.fail(file() + " was not written", "run the export again");
  }

  @Override
  public StepResult compensate(Context ctx, EventSink out) {
    try {
      RunFiles.delete(file());
      return StepResult.ok();
    } catch (IOException e) {
      return Failures.recoverable(
          "cannot delete " + file() + ": " + e.getMessage(),
          List.of(file()),
          "delete the file by hand");
    }
  }
}
