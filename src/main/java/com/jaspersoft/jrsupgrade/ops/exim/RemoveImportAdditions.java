package com.jaspersoft.jrsupgrade.ops.exim;

import com.jaspersoft.jrsupgrade.core.engine.CheckResult;
import com.jaspersoft.jrsupgrade.core.engine.Context;
import com.jaspersoft.jrsupgrade.core.engine.Step;
import com.jaspersoft.jrsupgrade.core.engine.StepResult;
import com.jaspersoft.jrsupgrade.core.event.EventSink;
import java.util.List;
import java.util.Objects;

/**
 * The rollback anchor of the {@code import} phase of a plan made with {@code --no-snapshot}
 * (ADR-0040), in place of {@link RestoreFromPreImportSnapshot}: its {@link #execute} writes
 * nothing, and its {@link #compensate} deletes what the failed import created under the listed
 * folders, judged against the listing {@link RecordRepositoryListing} took just before the import
 * (issue #100, ADR-0031). There is no snapshot, so what the import overwrote stays overwritten.
 * Invariants: the step declares itself mutating although it writes nothing, because the Runner
 * compensates mutating steps only; it is placed before the strategy's stop-service step, so when
 * the Runner compensates in reverse the service has been started again before the REST deletions
 * run; a second compensation finds nothing new and deletes nothing twice.
 */
final class RemoveImportAdditions implements Step {

  static final String ID = "import.additions-rollback";

  private final String phase;
  private final List<String> roots;

  RemoveImportAdditions(String phase, List<String> roots) {
    this.phase = Objects.requireNonNull(phase, "phase");
    this.roots = List.copyOf(Objects.requireNonNull(roots, "roots"));
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public String title() {
    return "Enable rollback of additions";
  }

  @Override
  public String phase() {
    return phase;
  }

  @Override
  public String detail() {
    return "deletes what the import creates under "
        + String.join(", ", roots)
        + " on failure (no snapshot: overwritten resources are not put back)";
  }

  /** True although nothing is written: the Runner compensates mutating steps only. */
  @Override
  public boolean mutating() {
    return true;
  }

  @Override
  public CheckResult precheck(Context ctx) {
    return CheckResult.pass();
  }

  @Override
  public StepResult execute(Context ctx, EventSink out) {
    EximLogs.info(
        out,
        ctx,
        this,
        "rollback point without a snapshot: a failure deletes what the import creates under "
            + String.join(", ", roots));
    return StepResult.ok();
  }

  @Override
  public StepResult compensate(Context ctx, EventSink out) {
    RecordRepositoryListing.deleteAdditions(ctx, out, this, roots);
    EximLogs.warn(
        out,
        ctx,
        this,
        "--no-snapshot: resources the failed import overwrote under "
            + String.join(", ", roots)
            + " are not put back");
    return StepResult.ok();
  }
}
