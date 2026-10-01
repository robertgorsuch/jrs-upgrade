package com.jaspersoft.jrsupgrade.ops.exim;

import com.jaspersoft.jrsupgrade.core.engine.CheckResult;
import com.jaspersoft.jrsupgrade.core.engine.Context;
import com.jaspersoft.jrsupgrade.core.engine.Step;
import com.jaspersoft.jrsupgrade.core.engine.StepResult;
import com.jaspersoft.jrsupgrade.core.event.EventSink;
import com.jaspersoft.jrsupgrade.jrs.api.ExportImportStrategy;
import com.jaspersoft.jrsupgrade.jrs.api.ExportRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 * The {@code backup} phase of an import plan (spec §9.4): this step announces what is about to be
 * snapshotted and {@link #steps} follows it with the chosen strategy's own export steps, moved into
 * the {@code backup} phase, so the snapshot is taken with the same strategy as the import and is
 * itself a normal export with a sidecar. Invariants: this step is read-only (the export steps do
 * the writing and carry their own compensation, which deletes the snapshot if the phase fails); the
 * snapshot path is fixed at planning time so the plan summary can list it under backups and {@code
 * runs recover} rebuilds the same plan.
 */
final class PreImportSnapshot implements Step {

  static final String ID = "backup.pre-import-snapshot";

  private final ExportRequest request;
  private final ExportImportStrategy.Kind kind;

  PreImportSnapshot(ExportRequest request, ExportImportStrategy.Kind kind) {
    this.request = Objects.requireNonNull(request, "request");
    this.kind = Objects.requireNonNull(kind, "kind");
  }

  /** This step followed by the strategy's export steps, all in the {@code backup} phase. */
  static List<Step> steps(ExportImportStrategy strategy, ExportRequest request) {
    List<Step> steps = new ArrayList<>();
    steps.add(new PreImportSnapshot(request, strategy.kind()));
    for (Step s : strategy.exportSteps(request)) {
      steps.add(Rephased.into(DefaultExportImportOperations.BACKUP_PHASE, s));
    }
    return List.copyOf(steps);
  }

  static String describe(ExportRequest request) {
    return request.fullServer() || request.scope() == ExportRequest.Scope.EVERYTHING
        ? "full server"
        : String.join(", ", new TreeSet<>(request.uris()));
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public String title() {
    return "Pre-import snapshot";
  }

  @Override
  public String phase() {
    return DefaultExportImportOperations.BACKUP_PHASE;
  }

  @Override
  public String detail() {
    return describe(request) + " -> " + request.output();
  }

  @Override
  public boolean mutating() {
    return false;
  }

  @Override
  public CheckResult precheck(Context ctx) {
    Path parent = request.output().toAbsolutePath().getParent();
    if (parent != null && Files.exists(parent) && !Files.isDirectory(parent)) {
      return CheckResult.fail(
          parent + " is not a directory", "remove the file that blocks the snapshot directory");
    }
    if (Files.exists(request.output())) {
      return CheckResult.warn(
          "an earlier snapshot of this archive at " + request.output() + " will be replaced");
    }
    return CheckResult.pass();
  }

  @Override
  public StepResult execute(Context ctx, EventSink out) {
    EximLogs.info(
        out,
        ctx,
        this,
        "snapshotting "
            + describe(request)
            + " with the "
            + (kind == ExportImportStrategy.Kind.REST ? "REST" : "vendor CLI")
            + " strategy to "
            + request.output());
    return StepResult.ok();
  }

  @Override
  public StepResult compensate(Context ctx, EventSink out) {
    return StepResult.ok();
  }
}
