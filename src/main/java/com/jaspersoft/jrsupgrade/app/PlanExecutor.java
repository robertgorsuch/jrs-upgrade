package com.jaspersoft.jrsupgrade.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.jaspersoft.jrsupgrade.core.engine.CancellationToken;
import com.jaspersoft.jrsupgrade.core.engine.Context;
import com.jaspersoft.jrsupgrade.core.engine.LockHeldException;
import com.jaspersoft.jrsupgrade.core.engine.Plan;
import com.jaspersoft.jrsupgrade.core.engine.PlanFingerprint;
import com.jaspersoft.jrsupgrade.core.engine.RunOptions;
import com.jaspersoft.jrsupgrade.core.engine.RunOutcome;
import com.jaspersoft.jrsupgrade.core.engine.RunRecord;
import com.jaspersoft.jrsupgrade.core.engine.Runner;
import com.jaspersoft.jrsupgrade.core.event.EventBus;
import com.jaspersoft.jrsupgrade.core.json.Json;
import com.jaspersoft.jrsupgrade.core.redact.Redactor;
import com.jaspersoft.jrsupgrade.ops.PlanRegistry;
import com.jaspersoft.jrsupgrade.ops.RunService;
import com.jaspersoft.jrsupgrade.ops.Services;
import com.jaspersoft.jrsupgrade.ops.retention.RetentionPruner;
import java.io.PrintWriter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * The one path every mutating command takes from a {@link Plan} to a process exit code (spec §5.5,
 * §6). Invariants: a pending run blocks every new mutating command with exit 8 and the exact {@code
 * runs recover} command; the plan is stored with a 30-minute TTL before it is shown, so {@code
 * --plan} output can be rebuilt later; nothing runs without {@code --yes} or an explicit
 * confirmation, and a non-interactive caller without {@code --yes} exits 2; the plan is claimed for
 * its run id before the first step so it can never execute twice and survives plan expiry while the
 * run is pending; the fingerprint is recomputed after the operator's answer by rebuilding the plan
 * from its stored arguments, as runs recover does, so inputs that changed while the prompt waited
 * are refused with exit 2 before anything is claimed (spec §6.2); Ctrl-C cancels through the run's
 * single {@link CancellationToken} and waits up to 30 s for the in-flight step to finish or
 * compensate; the exit code is {@link RunOutcome#exitCode()}, or 9 when the run lock is held. The
 * pending-run gate, plan storage, claim and run context come from {@link RunService}.
 */
final class PlanExecutor {

  static final Duration PLAN_TTL = RunService.PLAN_TTL;
  static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(30);

  /** What a command asks the executor to do with a plan. */
  record Request(
      Plan plan, String operation, String argsJson, boolean showOnly, boolean rollbackAll) {
    Request {
      Objects.requireNonNull(plan, "plan");
      Objects.requireNonNull(operation, "operation");
      Objects.requireNonNull(argsJson, "argsJson");
    }
  }

  private final Services services;
  private final RunService runs;
  private final GlobalOptions global;
  private final PrintWriter out;
  private final PrintWriter err;
  private final Ansi ansi;
  private final Redactor redactor;
  private final PlanRegistry registry;

  PlanExecutor(
      Services services,
      GlobalOptions global,
      PrintWriter out,
      PrintWriter err,
      Map<String, String> env) {
    this.services = Objects.requireNonNull(services, "services");
    this.runs = new RunService(services);
    this.registry =
        new PlanRegistry(
            () -> EximOps.open(services),
            () -> new com.jaspersoft.jrsupgrade.ops.upgrade.DefaultUpgradeOperations(services));
    this.global = Objects.requireNonNull(global, "global");
    this.out = Objects.requireNonNull(out, "out");
    this.err = Objects.requireNonNull(err, "err");
    this.ansi = Ansi.forStdout(global, env);
    this.redactor = services.redactor();
  }

  /** Shows, confirms and runs a fresh plan; returns the process exit code. */
  int execute(Request request) {
    // review 5.4: a run another process is executing right now holds the lock and is not a run
    // that needs recovery. Checking the lock first stops a second jrs-upgrade telling the operator
    // to
    // `runs recover --resume` a run that is running perfectly well in the first one.
    Optional<com.jaspersoft.jrsupgrade.core.engine.RunLock.Holder> holder = runs.lockHolder();
    if (holder.isPresent()) {
      return fail(
          ExitCodes.LOCK_HELD,
          "the run lock is held by run "
              + holder.get().runId()
              + " (pid "
              + holder.get().pid()
              + ")",
          Optional.of("wait for that jrs-upgrade process to finish, then run this command again"),
          Map.of("holderRunId", holder.get().runId(), "holderPid", holder.get().pid()));
    }
    List<RunRecord> pending = runs.pendingRuns();
    if (!pending.isEmpty()) {
      return pendingRuns(pending);
    }
    Plan plan = request.plan();
    String planJson = runs.storePlan(plan, request.operation(), request.argsJson()).planJson();
    if (global.json()) {
      // the stored (pretty) document re-emitted as one line so the whole run is JSONL:
      // plan, one event per line, then {"outcome": ...} or {"error": ...}
      out.println(redactor.redact(Json.write(Json.read(planJson, JsonNode.class))));
      out.flush();
    } else {
      PlanPrinter.print(out, plan, ansi, redactor);
    }
    if (request.showOnly()) {
      return ExitCodes.SUCCESS;
    }
    if (!global.yes()) {
      // --non-interactive and --json never prompt (review 4.4); an operator whose stdout is piped
      // still gets the question, on stdout, and answers on stdin (Confirm falls back from the
      // console to stdin; end of input is "no").
      if (global.json() || global.nonInteractive()) {
        return fail(
            ExitCodes.PRECHECK_FAILED,
            "confirmation required",
            Optional.of("pass --yes to run this plan without asking, or --plan to only show it"),
            Map.of());
      }
      out.println();
      if (!Confirm.ask(out, "Run this plan? [y/N] ")) {
        out.println("not run; nothing has changed");
        out.flush();
        return ExitCodes.SUCCESS;
      }
    }
    // spec §6.2: recomputed now, after the answer, by rebuilding the plan from its stored
    // arguments the way runs recover does. The CLI used to hand the runner the plan's own
    // fingerprint, so a target replaced while the prompt waited ran with stale before-hashes
    // (assessment item E2).
    PlanFingerprint recomputed;
    try {
      recomputed = registry.rebuild(request.operation(), request.argsJson()).fingerprint();
    } catch (RuntimeException e) {
      return fail(
          ExitCodes.PRECHECK_FAILED,
          "the plan's inputs can no longer be rebuilt: " + String.valueOf(e.getMessage()),
          Optional.of("plan again"),
          Map.of());
    }
    Optional<String> claimed = runs.claim(plan.planId());
    if (claimed.isEmpty()) {
      return fail(
          ExitCodes.PRECHECK_FAILED,
          "plan " + plan.planId() + " has expired or was already run",
          Optional.of("plan again"),
          Map.of());
    }
    Context ctx = runs.context(claimed.get());
    RunOptions opts = request.rollbackAll() ? RunOptions.withRollbackAll() : RunOptions.DEFAULT;
    return run(plan, ctx, runner -> runs.run(runner, plan, ctx, recomputed, opts));
  }

  /**
   * Resumes or rolls back a pending run through {@link
   * com.jaspersoft.jrsupgrade.core.engine.Recovery}; bypasses the pending check.
   */
  int recover(String runId, Plan plan, boolean resume) {
    Context ctx = runs.context(runId);
    return run(
        plan,
        ctx,
        runner ->
            resume
                ? runs.resume(runner, plan, runId, ctx)
                : runs.rollback(runner, plan, runId, ctx));
  }

  private int pendingRuns(List<RunRecord> pending) {
    String first = pending.get(0).runId();
    String remediation =
        "run `jrs-upgrade runs recover "
            + first
            + " --resume` to continue it, or `jrs-upgrade runs recover "
            + first
            + " --rollback` to undo it";
    if (global.json()) {
      List<Map<String, Object>> rows = new ArrayList<>();
      for (RunRecord run : pending) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("runId", run.runId());
        row.put("operation", run.operation());
        row.put("startedAt", run.startedAt());
        rows.add(row);
      }
      return fail(
          ExitCodes.RECOVERY_REQUIRED,
          pending.size()
              + (pending.size() == 1 ? " run needs" : " runs need")
              + " recovery before anything else can run",
          Optional.of(remediation),
          Map.of("pendingRuns", rows));
    }
    err.println(
        redactor.redact(
            "error: "
                + pending.size()
                + (pending.size() == 1 ? " run needs" : " runs need")
                + " recovery before anything else can run:"));
    for (RunRecord run : pending) {
      err.println(
          redactor.redact(
              "  " + run.runId() + "  " + run.operation() + "  started " + run.startedAt()));
    }
    err.println(remediation);
    err.flush();
    return ExitCodes.RECOVERY_REQUIRED;
  }

  private int run(Plan plan, Context ctx, Function<Runner, RunOutcome> body) {
    EventBus bus = new EventBus();
    ProgressRenderer renderer = new ProgressRenderer(plan, out, ansi, redactor, global.json());
    bus.subscribe(renderer);
    Runner runner = runs.runner(bus);
    if (!global.json()) {
      out.println();
      out.println("run " + ctx.runId());
      out.flush();
    }
    AtomicReference<RunOutcome> result = new AtomicReference<>();
    AtomicReference<RuntimeException> failure = new AtomicReference<>();
    Thread worker =
        new Thread(
            () -> {
              try {
                result.set(body.apply(runner));
              } catch (RuntimeException e) {
                failure.set(e);
              }
            },
            "jrs-upgrade-run");
    // Ctrl-C (review 4.3): the hook cancels, waits for the worker and for the outcome block, then
    // halts with the run's exit code; the JVM's own 130/143 never reaches the operator.
    RunGuard guard =
        new RunGuard(
            ctx.cancel(),
            worker,
            SHUTDOWN_GRACE,
            RunGuard.RENDER_GRACE,
            Runtime.getRuntime()::halt);
    Thread hook = new Thread(guard::onShutdown, "jrs-upgrade-shutdown");
    Runtime.getRuntime().addShutdownHook(hook);
    RunState.markStarted();
    worker.start();
    try {
      worker.join();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      ctx.cancel().cancel("interrupted");
      try {
        worker.join(SHUTDOWN_GRACE.toMillis());
      } catch (InterruptedException again) {
        Thread.currentThread().interrupt();
      }
    } finally {
      try {
        Runtime.getRuntime().removeShutdownHook(hook);
      } catch (IllegalStateException shuttingDown) {
        // the JVM is already going down; the hook is doing the cancelling
      }
    }
    RuntimeException thrown = failure.get();
    if (thrown != null) {
      if (thrown instanceof LockHeldException held) {
        return fail(
            ExitCodes.LOCK_HELD,
            "run lock is held by run " + held.holderRunId() + " (pid " + held.holderPid() + ")",
            Optional.of("wait for it to finish or check `jrs-upgrade runs list`"),
            Map.of("holderRunId", held.holderRunId(), "holderPid", held.holderPid()));
      }
      throw thrown;
    }
    RunOutcome outcome = result.get();
    if (outcome == null) {
      int code =
          fail(
              ExitCodes.CANCELLED,
              "run " + ctx.runId() + " was interrupted before it reported an outcome",
              Optional.empty(),
              Map.of());
      guard.rendered(code);
      return code;
    }
    renderer.outcome(ctx.runId(), outcome);
    out.flush();
    guard.rendered(outcome.exitCode());
    if (outcome instanceof RunOutcome.Succeeded) {
      // best effort, never changes the exit code; the run's own snapshots are protected explicitly
      RetentionPruner.of(services).afterSuccessfulRun(ctx.runId());
    }
    return outcome.exitCode();
  }

  /** Text: {@code error: message; remediation} on stderr. JSON: the error document on stdout. */
  private int fail(
      int code, String message, Optional<String> remediation, Map<String, Object> details) {
    return ExitCodes.fail(
        out, err, global.json(), code, ExitCodes.className(code), message, remediation, details);
  }
}
