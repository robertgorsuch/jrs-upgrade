package com.jaspersoft.jrsupgrade.core.engine;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Crash recovery for pending runs (spec §5.5, §6.6): lists runs without a terminal state, rebuilds
 * a run's step states from the journal, and either resumes from the interrupted step or rolls the
 * run back. Invariants: resume re-runs the interrupted step's precheck first and, if it fails,
 * returns {@link RunOutcome.PrecheckFailed} without touching the run so rollback stays available; a
 * run that already began rolling back can only be rolled back; both operations execute through the
 * {@link Runner}, so they hold the run lock and journal every transition.
 */
public final class Recovery {

  private final Journal store;
  private final Runner runner;

  public Recovery(Journal store, Runner runner) {
    this.store = Objects.requireNonNull(store, "store");
    this.runner = Objects.requireNonNull(runner, "runner");
  }

  /** Runs without a terminal state, oldest first. */
  public static List<RunRecord> pendingRuns(Journal store) {
    return store.pendingRuns();
  }

  public List<RunRecord> pendingRuns() {
    return store.pendingRuns();
  }

  /** Last recorded state of every step of {@code runId}, in first-seen order. */
  public Map<String, StepState> journal(String runId) {
    Map<String, StepState> last = new LinkedHashMap<>();
    for (Transition t : store.transitions(runId)) {
      last.put(t.stepId(), StepState.valueOf(t.toState()));
    }
    return Map.copyOf(last).isEmpty() ? Map.of() : java.util.Collections.unmodifiableMap(last);
  }

  /**
   * Index of the step a resume starts from: the last step recorded PENDING, RUNNING or FAILED, or
   * the step after the last SUCCEEDED one when the run stopped between steps.
   */
  public static int resumeIndex(Plan plan, Map<String, StepState> journal) {
    int interrupted = -1;
    int lastCompleted = -1;
    List<Step> steps = plan.steps();
    for (int i = 0; i < steps.size(); i++) {
      StepState st = journal.get(steps.get(i).id());
      if (st == null) {
        continue;
      }
      switch (st) {
        case PENDING, RUNNING, FAILED -> interrupted = i;
        case SUCCEEDED, SKIPPED -> lastCompleted = i;
        case ROLLED_BACK, ROLLBACK_FAILED -> {}
      }
    }
    return interrupted >= 0 ? interrupted : lastCompleted + 1;
  }

  /** Re-executes the interrupted step (idempotent) and continues the plan to the end. */
  public RunOutcome resume(Plan plan, String runId, Context ctx, RunOptions opts) {
    requirePending(runId);
    Map<String, StepState> journal = journal(runId);
    List<String> unknown = unknownSteps(plan, journal);
    if (!unknown.isEmpty()) {
      return planDrifted(runId, unknown);
    }
    Context runCtx = forRun(ctx, runId);
    if (journal.containsValue(StepState.ROLLED_BACK)
        || journal.containsValue(StepState.ROLLBACK_FAILED)) {
      return new RunOutcome.PrecheckFailed(
          firstRolledBack(journal),
          "run " + runId + " already began rolling back; resume is not available",
          "run `jrs-upgrade runs recover " + runId + " --rollback`");
    }
    int start = resumeIndex(plan, journal);
    if (start < plan.steps().size()) {
      Step step = plan.steps().get(start);
      CheckResult pre;
      try {
        pre = step.precheck(runCtx);
      } catch (RuntimeException e) {
        pre = CheckResult.fail(e.getClass().getSimpleName() + ": " + e.getMessage(), "");
      }
      if (pre instanceof CheckResult.Fail fail) {
        return new RunOutcome.PrecheckFailed(
            step.id(),
            "precheck of interrupted step " + step.id() + " failed: " + fail.message(),
            ("only rollback is available: run `jrs-upgrade runs recover "
                    + runId
                    + " --rollback`. "
                    + fail.remediation())
                .strip());
      }
    }
    return runner.resume(plan, runCtx, opts, start, journal);
  }

  /** Compensates every succeeded step of the run in reverse and ends it as ROLLED_BACK. */
  public RunOutcome rollback(Plan plan, String runId, Context ctx) {
    requirePending(runId);
    Map<String, StepState> journal = journal(runId);
    List<String> unknown = unknownSteps(plan, journal);
    if (!unknown.isEmpty()) {
      return planDrifted(runId, unknown);
    }
    return runner.rollback(
        plan, forRun(ctx, runId), journal, "operator requested rollback of run " + runId);
  }

  /**
   * Journaled step ids the rebuilt plan does not carry (assessment item E3): the plan is rebuilt
   * from its stored arguments, and an installation that changed in between (a customization
   * registered under WEB-INF adds stop/start steps, say) yields ids the journal never saw, so the
   * resume index and the rollback targets would line up against the wrong steps.
   */
  static List<String> unknownSteps(Plan plan, Map<String, StepState> journal) {
    Set<String> known = new HashSet<>();
    for (Step step : plan.steps()) {
      known.add(step.id());
    }
    return journal.keySet().stream().filter(id -> !known.contains(id)).sorted().toList();
  }

  private static RunOutcome planDrifted(String runId, List<String> unknown) {
    return new RunOutcome.PrecheckFailed(
        unknown.get(0),
        "run "
            + runId
            + " journaled steps the rebuilt plan does not contain ("
            + String.join(", ", unknown)
            + "); the installation or its configuration changed since the run started, so the"
            + " stored plan cannot be replayed",
        "put the installation back the way it was when the run started and recover again, or"
            + " restore what `jrs-upgrade runs show "
            + runId
            + "` lists by hand");
  }

  private void requirePending(String runId) {
    RunRecord run =
        store.run(runId).orElseThrow(() -> new IllegalArgumentException("unknown run " + runId));
    if (!run.pending()) {
      throw new IllegalStateException(
          "run " + runId + " already ended with state " + run.terminalState().orElseThrow());
    }
  }

  private static Context forRun(Context ctx, String runId) {
    return ctx.runId().equals(runId)
        ? ctx
        : new Context(runId, ctx.home(), ctx.platform(), ctx.cancel(), ctx.services());
  }

  private static String firstRolledBack(Map<String, StepState> journal) {
    return journal.entrySet().stream()
        .filter(
            e -> e.getValue() == StepState.ROLLED_BACK || e.getValue() == StepState.ROLLBACK_FAILED)
        .map(Map.Entry::getKey)
        .findFirst()
        .orElse("");
  }
}
