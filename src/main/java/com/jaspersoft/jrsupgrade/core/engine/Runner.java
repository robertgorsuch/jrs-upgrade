package com.jaspersoft.jrsupgrade.core.engine;

import com.jaspersoft.jrsupgrade.core.event.Event;
import com.jaspersoft.jrsupgrade.core.event.EventSink;
import com.jaspersoft.jrsupgrade.core.redact.RedactingEventSink;
import com.jaspersoft.jrsupgrade.core.redact.Redactor;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Executes a {@link Plan} step by step under the run lock (spec §6.3-§6.6). Invariants: a plan
 * whose recomputed fingerprint differs is refused before anything is touched; every step state
 * change is journalled to {@code step_transitions} in its own transaction <em>before</em> the
 * matching event is emitted, so the journal is always at least as advanced as what observers saw;
 * {@code Retryable} failures are retried per the step's {@link RetryPolicy} and become {@code
 * Recoverable} when exhausted; {@code Recoverable} failures compensate the failing step itself when
 * it is mutating and its {@code execute} ran (a precheck failure never ran, so it is not
 * compensated), then every succeeded mutating step in reverse back to the failing step's phase
 * boundary (or the whole plan with {@code rollbackAll}); {@code Fatal} failures never compensate;
 * cancellation compensates the in-flight step and then every succeeded mutating step; irreversible
 * steps are skipped during compensation; the run row always receives a terminal state and exit code
 * before the Runner returns. Steps that throw are treated as {@code Recoverable} with the exception
 * as the cause. Every event, including those steps emit themselves, passes the redactor before any
 * subscriber sees it, so redaction is an engine guarantee rather than a per-sink convention. A
 * journal write that fails ends the run with a {@code Failed} outcome and a {@code RunFailed} event
 * naming {@code runs recover}, never with an escaping exception; cancellation is noticed inside a
 * retry backoff within one {@link Sleeper#SLICE}. {@link
 * com.jaspersoft.jrsupgrade.core.engine.LockHeldException} propagates untouched so the CLI can map
 * it to exit code 9.
 */
public final class Runner {

  /** Phase name carried by run-level events. */
  public static final String RUN_PHASE = "run";

  /** MDC key naming the run on every log line written while it executes (#160). */
  public static final String MDC_RUN_ID = "runId";

  private static final Logger LOG = LoggerFactory.getLogger(Runner.class);

  private final Journal store;
  private final EventSink sink;
  private final Clock clock;
  private final Sleeper sleeper;

  /** As below, redacting with the process-wide {@link Redactor#global()}. */
  public Runner(Journal store, EventSink sink, Clock clock, Sleeper sleeper) {
    this(store, sink, clock, sleeper, Redactor.global());
  }

  /**
   * Every event this runner emits, including those a step emits through the sink it is handed,
   * passes {@code redactor} before {@code sink} sees it. Subscribers therefore never need to redact
   * for themselves, and a new subscriber cannot leak by forgetting to.
   */
  public Runner(Journal store, EventSink sink, Clock clock, Sleeper sleeper, Redactor redactor) {
    this.store = Objects.requireNonNull(store, "store");
    this.sink =
        new RedactingEventSink(
            Objects.requireNonNull(sink, "sink"), Objects.requireNonNull(redactor, "redactor"));
    this.clock = Objects.requireNonNull(clock, "clock");
    this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
  }

  /** Runs a fresh plan; {@code ctx.runId()} names the run. */
  public RunOutcome run(Plan plan, Context ctx, PlanFingerprint recomputed, RunOptions opts) {
    if (!plan.fingerprint().matches(recomputed)) {
      return new RunOutcome.FingerprintMismatch(plan.fingerprint().changedKeys(recomputed));
    }
    try (RunLock unusedLock = new RunLock(ctx.home(), ctx.runId(), clock.instant());
        MDC.MDCCloseable unusedMdc = MDC.putCloseable(MDC_RUN_ID, ctx.runId())) {
      try {
        store.recordRunStart(
            ctx.runId(), plan.summary().operation(), Optional.of(plan.planId()), clock.instant());
      } catch (JournalException e) {
        // Nothing has been mutated and there is no row for runs recover to find, so this is a
        // refusal (exit 2), not the rollback-incomplete outcome a failure mid-run gets (item E5).
        return new RunOutcome.Failed(
            "the run journal could not be written: " + describe(e),
            false,
            "run jrs-upgrade doctor; state.db must be writable before anything can run; nothing was"
                + " changed",
            List.of());
      }
      LOG.info(
          "run {} started: {} (plan {}, {} steps)",
          ctx.runId(),
          plan.summary().operation(),
          plan.planId(),
          plan.steps().size());
      return new Execution(plan, ctx, opts, Map.of()).proceed(0);
    }
  }

  /**
   * Continues a pending run from {@code startIndex}. {@code journal} holds the last recorded state
   * of every step seen so far; steps recorded {@code SUCCEEDED} take part in any later rollback.
   */
  public RunOutcome resume(
      Plan plan, Context ctx, RunOptions opts, int startIndex, Map<String, StepState> journal) {
    if (startIndex < 0 || startIndex > plan.steps().size()) {
      throw new IllegalArgumentException("startIndex out of range: " + startIndex);
    }
    try (RunLock unusedLock = new RunLock(ctx.home(), ctx.runId(), clock.instant());
        MDC.MDCCloseable unusedMdc = MDC.putCloseable(MDC_RUN_ID, ctx.runId())) {
      LOG.info("run {} resumed at step {}", ctx.runId(), startIndex + 1);
      return new Execution(plan, ctx, opts, journal).proceed(startIndex);
    }
  }

  /**
   * Compensates every step of a pending run that the journal records as {@code SUCCEEDED}, and a
   * mutating step recorded {@code RUNNING} or {@code FAILED} (the process died before its
   * compensation ran), in reverse order, then ends the run. A compensation must therefore converge
   * from any partial state, including one where {@code execute} never started.
   */
  public RunOutcome rollback(Plan plan, Context ctx, Map<String, StepState> journal, String cause) {
    try (RunLock unusedLock = new RunLock(ctx.home(), ctx.runId(), clock.instant());
        MDC.MDCCloseable unusedMdc = MDC.putCloseable(MDC_RUN_ID, ctx.runId())) {
      LOG.info("run {} rolling back: {}", ctx.runId(), cause);
      return new Execution(plan, ctx, RunOptions.DEFAULT, journal).rollbackRecorded(cause);
    }
  }

  private String describe(RuntimeException e) {
    String msg = e.getMessage();
    return msg == null || msg.isBlank()
        ? e.getClass().getSimpleName()
        : e.getClass().getSimpleName() + ": " + msg;
  }

  /** Why a compensation could not complete. */
  private record RollbackFailure(String stepId, String cause, List<Path> backups) {}

  /** What happened to one step, as seen by the main loop. */
  private sealed interface StepOutcome {
    record Done() implements StepOutcome {}

    record PrecheckFailed(String stepId, String message, String remediation)
        implements StepOutcome {}

    /** {@code executed} is false when the step failed its precheck and never ran. */
    record Failure(StepFailure failure, boolean executed) implements StepOutcome {}

    record Cancelled(String reason, Optional<RollbackFailure> inFlight) implements StepOutcome {}
  }

  /** Mutable state of one execution; one instance per run/resume/rollback call. */
  private final class Execution {
    private final Plan plan;
    private final Context ctx;
    private final RunOptions opts;
    private final String runId;
    private final Instant runStart;
    private final Map<String, StepState> states = new HashMap<>();
    private final List<Integer> succeededMutating = new ArrayList<>();
    private boolean mutated;

    Execution(Plan plan, Context ctx, RunOptions opts, Map<String, StepState> journal) {
      this.plan = plan;
      this.ctx = ctx;
      this.opts = opts;
      this.runId = ctx.runId();
      this.runStart = clock.instant();
      states.putAll(journal);
      List<Step> steps = plan.steps();
      for (int i = 0; i < steps.size(); i++) {
        Step s = steps.get(i);
        StepState st = journal.get(s.id());
        if (st == null || !s.mutating()) {
          continue;
        }
        switch (st) {
          case SUCCEEDED -> {
            succeededMutating.add(i);
            mutated = true;
          }
          case RUNNING, FAILED, ROLLBACK_FAILED -> mutated = true;
          case PENDING, ROLLED_BACK, SKIPPED -> {}
        }
      }
    }

    RunOutcome proceed(int startIndex) {
      try {
        return proceedJournalled(startIndex);
      } catch (JournalException e) {
        return journalFailed(e);
      }
    }

    /**
     * The journal is the run's source of truth; when it cannot be written the run cannot go on and
     * cannot even record that it stopped. The outcome says so, names the run for {@code runs
     * recover}, and counts as rollback-incomplete whenever a mutating step already ran, because
     * nothing was undone. The terminal row is still attempted, since the failure may have been a
     * single write.
     */
    private RunOutcome journalFailed(JournalException e) {
      String cause = "the run journal could not be written: " + describe(e);
      String nextAction =
          "the state of run "
              + runId
              + " is unknown until state.db is writable again; run jrs-upgrade doctor, then"
              + " jrs-upgrade runs recover "
              + runId
              + " --resume or --rollback";
      RunOutcome.Failed outcome = new RunOutcome.Failed(cause, mutated, nextAction, List.of());
      try {
        ended(TerminalState.FAILED, outcome.exitCode());
      } catch (JournalException again) {
        // the journal is what failed; the pending row is what runs recover will find
      }
      emit(
          new Event.RunFailed(
              now(), runId, RUN_PHASE, cause, List.of(), nextAction, outcome.rollbackIncomplete()));
      return outcome;
    }

    private RunOutcome proceedJournalled(int startIndex) {
      emit(
          new Event.PlanCreated(
              now(), runId, RUN_PHASE, plan.planId(), plan.fingerprint().value()));
      List<Step> steps = plan.steps();
      for (int i = startIndex; i < steps.size(); i++) {
        if (ctx.cancel().isCancelled()) {
          return cancelled(ctx.cancel().reason(), Optional.empty());
        }
        StepOutcome outcome = runStep(i);
        int index = i;
        Optional<RunOutcome> terminal =
            switch (outcome) {
              case StepOutcome.Done d -> Optional.empty();
              case StepOutcome.PrecheckFailed p -> Optional.of(precheckFailed(p));
              case StepOutcome.Failure f -> Optional.of(failed(index, f.failure(), f.executed()));
              case StepOutcome.Cancelled c -> Optional.of(cancelled(c.reason(), c.inFlight()));
            };
        if (terminal.isPresent()) {
          return terminal.get();
        }
      }
      return succeeded();
    }

    RunOutcome rollbackRecorded(String cause) {
      try {
        return rollbackRecordedJournalled(cause);
      } catch (JournalException e) {
        return journalFailed(e);
      }
    }

    private RunOutcome rollbackRecordedJournalled(String cause) {
      List<Step> steps = plan.steps();
      List<Integer> targets = new ArrayList<>(succeededMutating);
      for (int i = 0; i < steps.size(); i++) {
        StepState st = states.get(steps.get(i).id());
        // A step whose compensation already failed once is tried again, not skipped: skipping it
        // ended the run as ROLLED_BACK with its partial change standing (assessment item E4).
        // Compensations are idempotent (spec §6), so the retry is safe; a second failure ends the
        // run as rollback-incomplete, exit 4, naming the step.
        boolean pending =
            st == StepState.RUNNING || st == StepState.FAILED || st == StepState.ROLLBACK_FAILED;
        if (pending && steps.get(i).mutating()) {
          targets.add(i);
        }
      }
      targets.sort(null);
      Optional<RollbackFailure> failure = compensate(targets);
      if (failure.isPresent()) {
        RollbackFailure rf = failure.get();
        return finishFailed(
            new RunOutcome.Failed(
                "rollback of run "
                    + runId
                    + " incomplete at step "
                    + rf.stepId()
                    + ": "
                    + rf.cause(),
                true,
                "restore the listed backups manually, then run `jrs-upgrade doctor`",
                rf.backups()));
      }
      String phase = steps.isEmpty() ? RUN_PHASE : steps.get(0).phase();
      return finishRolledBack(phase, cause);
    }

    private StepOutcome runStep(int index) {
      Step step = plan.steps().get(index);
      Optional<String> stepId = Optional.of(step.id());
      transition(step, StepState.PENDING, Optional.empty());
      emit(new Event.StepPending(now(), runId, stepId, step.phase(), step.title()));

      CheckResult pre = check(() -> step.precheck(ctx));
      switch (pre) {
        case CheckResult.Pass p -> {}
        case CheckResult.Warn w ->
            emit(
                new Event.Log(
                    now(), runId, stepId, step.phase(), Event.Log.Level.WARN, w.message()));
        case CheckResult.Fail f -> {
          String cause = "precheck failed: " + f.message();
          transition(step, StepState.FAILED, Optional.of(cause));
          StepFailure.Recoverable failure = StepFailure.recoverable(cause, f.remediation());
          emit(new Event.StepFailed(now(), runId, stepId, step.phase(), failure));
          if (!mutated) {
            return new StepOutcome.PrecheckFailed(step.id(), f.message(), f.remediation());
          }
          return new StepOutcome.Failure(failure, false);
        }
      }

      transition(step, StepState.RUNNING, Optional.empty());
      emit(new Event.StepRunning(now(), runId, stepId, step.phase(), step.title()));
      Instant started = now();
      if (step.mutating()) {
        mutated = true;
      }
      RetryPolicy policy = step.retryPolicy();
      int attempt = 1;
      while (true) {
        StepResult result;
        try {
          result = step.execute(ctx, sink);
        } catch (CancellationToken.CancelledException e) {
          return cancelInFlight(step, e.getMessage());
        } catch (RuntimeException e) {
          result =
              StepResult.failed(
                  StepFailure.recoverable(
                      describe(e), "inspect the log for step " + step.id() + " and retry the run"));
        }
        Optional<StepFailure> failure =
            switch (result) {
              case StepResult.Ok ok -> postcheckFailure(step);
              case StepResult.Failed f -> Optional.of(f.failure());
            };
        if (failure.isEmpty()) {
          transition(step, StepState.SUCCEEDED, Optional.empty());
          emit(
              new Event.StepSucceeded(
                  now(), runId, stepId, step.phase(), Duration.between(started, now()).toMillis()));
          if (step.mutating()) {
            succeededMutating.add(index);
          }
          return new StepOutcome.Done();
        }
        StepFailure sf = failure.get();
        if (sf instanceof StepFailure.Retryable retryable && attempt < policy.maxAttempts()) {
          attempt++;
          // A Retry-After from the server is a floor under the policy's backoff (review 2.2).
          Duration backoff = policy.delayBefore(attempt);
          Duration delay =
              retryable.retryAfter().filter(d -> d.compareTo(backoff) > 0).orElse(backoff);
          transition(
              step,
              StepState.RUNNING,
              Optional.of(
                  "retry " + attempt + "/" + policy.maxAttempts() + ": " + retryable.cause()));
          emit(
              new Event.StepRetry(
                  now(),
                  runId,
                  stepId,
                  step.phase(),
                  attempt,
                  policy.maxAttempts(),
                  delay.toMillis(),
                  retryable.cause()));
          try {
            sleeper.sleep(delay, ctx.cancel());
          } catch (CancellationToken.CancelledException e) {
            return cancelInFlight(step, e.getMessage());
          }
          continue;
        }
        int attempts = attempt;
        StepFailure finalFailure =
            switch (sf) {
              case StepFailure.Retryable r ->
                  new StepFailure.Recoverable(
                      r.cause() + " (gave up after " + attempts + " attempts)",
                      r.affectedPaths(),
                      r.affectedUris(),
                      r.backups(),
                      r.nextAction());
              case StepFailure.Recoverable r -> r;
              case StepFailure.Fatal r -> r;
            };
        transition(step, StepState.FAILED, Optional.of(finalFailure.cause()));
        emit(new Event.StepFailed(now(), runId, stepId, step.phase(), finalFailure));
        return new StepOutcome.Failure(finalFailure, true);
      }
    }

    private Optional<StepFailure> postcheckFailure(Step step) {
      CheckResult post = check(() -> step.postcheck(ctx));
      return switch (post) {
        case CheckResult.Pass p -> Optional.empty();
        case CheckResult.Warn w -> {
          emit(
              new Event.Log(
                  now(),
                  runId,
                  Optional.of(step.id()),
                  step.phase(),
                  Event.Log.Level.WARN,
                  w.message()));
          yield Optional.empty();
        }
        case CheckResult.Fail f ->
            Optional.of(
                StepFailure.recoverable("postcheck failed: " + f.message(), f.remediation()));
      };
    }

    private StepOutcome cancelInFlight(Step step, String reason) {
      String why = reason == null || reason.isBlank() ? "cancelled" : reason;
      transition(step, StepState.FAILED, Optional.of("cancelled: " + why));
      emit(
          new Event.StepFailed(
              now(),
              runId,
              Optional.of(step.id()),
              step.phase(),
              StepFailure.recoverable(
                  "cancelled: " + why, "none; jrs-upgrade compensates the step automatically")));
      Optional<RollbackFailure> inFlight = step.mutating() ? compensateOne(step) : Optional.empty();
      return new StepOutcome.Cancelled(why, inFlight);
    }

    private RunOutcome failed(int index, StepFailure failure, boolean executed) {
      return switch (failure) {
        case StepFailure.Recoverable r -> {
          boolean whole = opts.rollbackAll() || plan.steps().get(index).rollbackAllOnFailure();
          int from = whole ? 0 : phaseStart(index);
          List<Integer> targets =
              new ArrayList<>(succeededMutating.stream().filter(i -> i >= from).toList());
          // The step that failed part-way through its work is undone first; one that never ran
          // its execute (precheck failure) has nothing to undo.
          if (executed && plan.steps().get(index).mutating()) {
            targets.add(index);
          }
          targets.sort(null);
          Optional<RollbackFailure> rf = compensate(targets);
          if (rf.isPresent()) {
            List<Path> backups = new ArrayList<>(r.backups());
            backups.addAll(rf.get().backups());
            yield finishFailed(
                new RunOutcome.Failed(
                    r.cause()
                        + "; rollback incomplete at step "
                        + rf.get().stepId()
                        + ": "
                        + rf.get().cause(),
                    true,
                    r.nextAction(),
                    backups));
          }
          yield finishRolledBack(plan.steps().get(from).phase(), r.cause());
        }
        case StepFailure.Fatal f ->
            finishFailed(new RunOutcome.Failed(f.cause(), mutated, f.nextAction(), f.backups()));
        case StepFailure.Retryable r ->
            throw new IllegalStateException("retryable failures are converted before this point");
      };
    }

    private RunOutcome cancelled(String reason, Optional<RollbackFailure> inFlight) {
      Optional<RollbackFailure> rf =
          inFlight.isPresent() ? inFlight : compensate(List.copyOf(succeededMutating));
      if (rf.isPresent()) {
        return finishFailed(
            new RunOutcome.Failed(
                "cancelled: "
                    + reason
                    + "; rollback incomplete at step "
                    + rf.get().stepId()
                    + ": "
                    + rf.get().cause(),
                true,
                "restore the listed backups manually, then run `jrs-upgrade doctor`",
                rf.get().backups()));
      }
      return finishCancelled(reason);
    }

    private int phaseStart(int index) {
      List<Step> steps = plan.steps();
      String phase = steps.get(index).phase();
      int start = index;
      while (start > 0 && steps.get(start - 1).phase().equals(phase)) {
        start--;
      }
      return start;
    }

    /** Compensates the given step indices in reverse order; stops at the first failure. */
    private Optional<RollbackFailure> compensate(List<Integer> indices) {
      List<Step> steps = plan.steps();
      for (int k = indices.size() - 1; k >= 0; k--) {
        Optional<RollbackFailure> rf = compensateOne(steps.get(indices.get(k)));
        if (rf.isPresent()) {
          return rf;
        }
      }
      return Optional.empty();
    }

    private Optional<RollbackFailure> compensateOne(Step step) {
      Optional<String> stepId = Optional.of(step.id());
      if (step.irreversible()) {
        transition(step, StepState.SKIPPED, Optional.of("irreversible"));
        emit(new Event.StepSkipped(now(), runId, stepId, step.phase(), "irreversible"));
        return Optional.empty();
      }
      Instant started = now();
      StepResult result;
      try {
        result = step.compensate(ctx, sink);
      } catch (RuntimeException e) {
        result =
            StepResult.failed(
                StepFailure.recoverable(describe(e), "restore step " + step.id() + " manually"));
      }
      return switch (result) {
        case StepResult.Ok ok -> {
          transition(step, StepState.ROLLED_BACK, Optional.empty());
          emit(
              new Event.StepRolledBack(
                  now(), runId, stepId, step.phase(), Duration.between(started, now()).toMillis()));
          yield Optional.empty();
        }
        case StepResult.Failed f -> {
          transition(step, StepState.ROLLBACK_FAILED, Optional.of(f.failure().cause()));
          emit(
              new Event.StepRollbackFailed(
                  now(), runId, stepId, step.phase(), f.failure().cause(), f.failure().backups()));
          yield Optional.of(
              new RollbackFailure(step.id(), f.failure().cause(), f.failure().backups()));
        }
      };
    }

    private RunOutcome succeeded() {
      ended(TerminalState.SUCCEEDED, 0);
      emit(
          new Event.RunSucceeded(
              now(), runId, RUN_PHASE, Duration.between(runStart, now()).toMillis()));
      return new RunOutcome.Succeeded();
    }

    private RunOutcome precheckFailed(StepOutcome.PrecheckFailed p) {
      RunOutcome.PrecheckFailed outcome =
          new RunOutcome.PrecheckFailed(p.stepId(), p.message(), p.remediation());
      ended(TerminalState.PRECHECK_FAILED, outcome.exitCode());
      emit(
          new Event.RunFailed(
              now(),
              runId,
              RUN_PHASE,
              "precheck of step " + p.stepId() + " failed: " + p.message(),
              List.of(),
              p.remediation(),
              false));
      return outcome;
    }

    private RunOutcome finishRolledBack(String phase, String cause) {
      RunOutcome.RolledBack outcome = new RunOutcome.RolledBack(phase, cause);
      ended(TerminalState.ROLLED_BACK, outcome.exitCode());
      emit(new Event.RunRolledBack(now(), runId, RUN_PHASE, phase, cause));
      return outcome;
    }

    private RunOutcome finishFailed(RunOutcome.Failed outcome) {
      ended(TerminalState.FAILED, outcome.exitCode());
      emit(
          new Event.RunFailed(
              now(),
              runId,
              RUN_PHASE,
              outcome.cause(),
              outcome.backups(),
              outcome.nextAction(),
              outcome.rollbackIncomplete()));
      return outcome;
    }

    private RunOutcome finishCancelled(String reason) {
      RunOutcome.Cancelled outcome = new RunOutcome.Cancelled(reason);
      ended(TerminalState.CANCELLED, outcome.exitCode());
      emit(new Event.RunCancelled(now(), runId, RUN_PHASE, reason));
      return outcome;
    }

    private void transition(Step step, StepState to, Optional<String> detail) {
      Optional<String> from = Optional.ofNullable(states.get(step.id())).map(StepState::name);
      store.appendTransition(runId, step.id(), step.phase(), from, to.name(), detail);
      states.put(step.id(), to);
      // after the journal, like every event: the log is never ahead of state.db
      LOG.info(
          "step {} [{}] {} -> {}{}",
          step.id(),
          step.phase(),
          from.orElse("-"),
          to.name(),
          detail.map(d -> ": " + d).orElse(""));
    }

    /** Records the run's end in the journal, then logs it. */
    private void ended(TerminalState state, int exitCode) {
      store.recordRunEnd(runId, now(), state, exitCode);
      LOG.info("run {} ended {} (exit {})", runId, state, exitCode);
    }

    private CheckResult check(Supplier<CheckResult> check) {
      try {
        return check.get();
      } catch (RuntimeException e) {
        return CheckResult.fail(describe(e), "inspect the log and fix the reported condition");
      }
    }

    private void emit(Event event) {
      sink.emit(event);
    }

    private Instant now() {
      return clock.instant();
    }
  }
}
