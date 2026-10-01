package com.jaspersoft.jrsupgrade.app;

import com.jaspersoft.jrsupgrade.core.engine.CheckResult;
import com.jaspersoft.jrsupgrade.core.engine.Context;
import com.jaspersoft.jrsupgrade.core.engine.Plan;
import com.jaspersoft.jrsupgrade.core.engine.PlanFingerprint;
import com.jaspersoft.jrsupgrade.core.engine.PlanSummary;
import com.jaspersoft.jrsupgrade.core.engine.Step;
import com.jaspersoft.jrsupgrade.core.engine.StepFailure;
import com.jaspersoft.jrsupgrade.core.engine.StepResult;
import com.jaspersoft.jrsupgrade.core.event.EventSink;
import com.jaspersoft.jrsupgrade.jrs.strategy.Sidecar;
import com.jaspersoft.jrsupgrade.ops.exim.DefaultExportImportOperations;
import com.jaspersoft.jrsupgrade.ops.exim.ExportImportOperations;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * An {@link ExportImportOperations} for CLI tests: records the options it was planned with and
 * returns small plans of recording steps shaped like the real ones (one export phase; precheck,
 * backup and import phases), one of which can be made to fail.
 */
public final class FakeExportImportOperations implements ExportImportOperations {

  public static final List<String> EXPORT_STEPS =
      List.of("export.start", "export.poll", "export.download", "export.sidecar");
  public static final List<String> IMPORT_STEPS =
      List.of(
          "precheck.import.check-keystore",
          "backup.pre-import-snapshot",
          "backup.export.start",
          "import.snapshot-rollback",
          "import.start",
          "import.poll");

  public volatile Optional<String> failStep = Optional.empty();
  public volatile Optional<RuntimeException> planFailure = Optional.empty();
  public volatile Optional<ExportOptions> lastExport = Optional.empty();
  public volatile Optional<ImportOptions> lastImport = Optional.empty();
  public volatile String lastPlanId = "";
  public final List<String> executed = new CopyOnWriteArrayList<>();
  public final List<String> compensated = new CopyOnWriteArrayList<>();

  @Override
  public Plan planExport(ExportOptions options) {
    planFailure.ifPresent(
        e -> {
          throw e;
        });
    lastExport = Optional.of(options);
    Path out = options.out().toAbsolutePath().normalize();
    List<Step> steps =
        List.of(
            step("export.start", "export", "Start export task", "uris " + options.uris()),
            step("export.poll", "export", "Wait for export task", "timeout 120m"),
            step("export.download", "export", "Download export archive", out.toString()),
            step("export.sidecar", "export", "Write export sidecar", ""));
    PlanSummary summary =
        new PlanSummary(
            "export",
            options.fullServer() ? "full server" : String.join(", ", options.uris()),
            List.of(out, Sidecar.pathFor(out)),
            List.copyOf(options.uris()),
            false,
            List.of(),
            Map.of("export", "delete the partial archive and its sidecar"),
            "rest (REST: EXPORT_ASYNC probe passed, service stays up)",
            List.of());
    lastPlanId = "fake-export-" + UUID.randomUUID();
    return new Plan(lastPlanId, steps, summary, PlanFingerprint.of(Map.of("out", out.toString())));
  }

  @Override
  public Plan planImport(ImportOptions options) {
    planFailure.ifPresent(
        e -> {
          throw e;
        });
    lastImport = Optional.of(options);
    Path archive = options.archive().toAbsolutePath().normalize();
    Path snapshot = Path.of("snapshots", "pre-import", "pre-import-x.zip");
    List<Step> steps =
        List.of(
            step("precheck.import.check-keystore", "precheck", "Check keystore fingerprint", ""),
            step("backup.pre-import-snapshot", "backup", "Pre-import snapshot", "/public -> x"),
            step("backup.export.start", "backup", "Start export task", "uris /public"),
            step("import.snapshot-rollback", "import", "Enable snapshot rollback", ""),
            step("import.start", "import", "Start import task", archive.toString()),
            step("import.poll", "import", "Wait for import task", "timeout 120m"));
    PlanSummary summary =
        new PlanSummary(
            "import",
            archive.getFileName().toString(),
            List.of(),
            List.of("/public"),
            false,
            List.of(snapshot),
            Map.of("import", "re-import the pre-import snapshot with update (best effort)"),
            "rest (REST: IMPORT_ASYNC probe passed, service stays up)",
            List.of(DefaultExportImportOperations.ROLLBACK_WARNING));
    lastPlanId = "fake-import-" + UUID.randomUUID();
    return new Plan(
        lastPlanId, steps, summary, PlanFingerprint.of(Map.of("archive", archive.toString())));
  }

  private Step step(String id, String phase, String title, String detail) {
    return new FakeStep(id, phase, title, detail);
  }

  /** Records execution and compensation; fails when its id is {@link #failStep}. */
  private final class FakeStep implements Step {
    private final String id;
    private final String phase;
    private final String title;
    private final String detail;

    FakeStep(String id, String phase, String title, String detail) {
      this.id = id;
      this.phase = phase;
      this.title = title;
      this.detail = detail;
    }

    @Override
    public String id() {
      return id;
    }

    @Override
    public String title() {
      return title;
    }

    @Override
    public String phase() {
      return phase;
    }

    @Override
    public String detail() {
      return detail;
    }

    @Override
    public CheckResult precheck(Context ctx) {
      return CheckResult.pass();
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      executed.add(id);
      if (failStep.map(id::equals).orElse(false)) {
        return StepResult.failed(
            new StepFailure.Recoverable(
                "simulated failure of " + id,
                List.of(),
                List.of(),
                List.of(Path.of("snapshots", "pre-import", "pre-import-x.zip")),
                "inspect the fake and retry"));
      }
      return StepResult.ok();
    }

    @Override
    public StepResult compensate(Context ctx, EventSink out) {
      compensated.add(id);
      return StepResult.ok();
    }
  }
}
