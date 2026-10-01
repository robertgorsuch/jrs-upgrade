package com.jaspersoft.jrsupgrade.ops;

import com.jaspersoft.jrsupgrade.core.config.Config;
import com.jaspersoft.jrsupgrade.core.engine.CancellationToken;
import com.jaspersoft.jrsupgrade.core.engine.Context;
import com.jaspersoft.jrsupgrade.core.engine.Plan;
import com.jaspersoft.jrsupgrade.core.engine.PlanFingerprint;
import com.jaspersoft.jrsupgrade.core.engine.Recovery;
import com.jaspersoft.jrsupgrade.core.engine.RunIds;
import com.jaspersoft.jrsupgrade.core.engine.RunOptions;
import com.jaspersoft.jrsupgrade.core.engine.RunOutcome;
import com.jaspersoft.jrsupgrade.core.engine.RunRecord;
import com.jaspersoft.jrsupgrade.core.engine.Runner;
import com.jaspersoft.jrsupgrade.core.engine.Sleeper;
import com.jaspersoft.jrsupgrade.core.event.EventSink;
import com.jaspersoft.jrsupgrade.core.keys.KeyRing;
import com.jaspersoft.jrsupgrade.core.redact.Redactor;
import com.jaspersoft.jrsupgrade.core.secrets.SecretResolver;
import com.jaspersoft.jrsupgrade.core.snapshot.SnapshotStore;
import com.jaspersoft.jrsupgrade.core.state.StateStore;
import com.jaspersoft.jrsupgrade.core.state.StoredPlan;
import com.jaspersoft.jrsupgrade.jrs.api.JrsAdapter;
import com.jaspersoft.jrsupgrade.ops.exim.DeferredJrsAdapter;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The parts of "plan to run" that the CLI ({@link PlanExecutor}) and {@code runs recover} share
 * (spec §5.5, §6.2, §13.1): the pending-run gate, plan storage with its 30-minute TTL, the one-shot
 * claim of a plan for a run id, the run {@link Context} with every service a step may need, and the
 * {@link Runner} construction. Invariants: a plan is claimed at most once ({@link #claim} returns
 * empty when it is unknown, expired or already consumed); every context carries {@link Services},
 * {@link StateStore}, {@link Config}, {@link SnapshotStore} and {@link KeyRing}; nothing here
 * prints, prompts or decides exit codes, so the two front ends keep their own presentation.
 */
public final class RunService {

  /** How long a stored plan may be run after it was shown (spec §6.2). */
  public static final Duration PLAN_TTL = Duration.ofMinutes(30);

  private final Services services;

  public RunService(Services services) {
    this.services = Objects.requireNonNull(services, "services");
  }

  public Services services() {
    return services;
  }

  public StateStore store() {
    return services.stateStore().get();
  }

  /** Runs without a terminal state, oldest first; non-empty blocks every new run (spec §5.5). */
  public List<RunRecord> pendingRuns() {
    return Recovery.pendingRuns(store());
  }

  /** Who is executing a run in another process right now, if anyone (review 5.4). */
  public Optional<com.jaspersoft.jrsupgrade.core.engine.RunLock.Holder> lockHolder() {
    return com.jaspersoft.jrsupgrade.core.engine.RunLock.heldBy(services.home().runLock());
  }

  /** Expires stale plans, stores {@code plan} with the TTL and returns the stored row. */
  public StoredPlan storePlan(Plan plan, String operation, String argsJson) {
    StateStore store = store();
    Instant now = services.clock().instant();
    store.expirePlans(now);
    StoredPlan stored =
        new StoredPlan(
            plan.planId(),
            operation,
            argsJson,
            PlanJson.toJson(plan),
            plan.fingerprint().value(),
            now,
            now.plus(PLAN_TTL),
            Optional.empty());
    store.savePlan(stored);
    return stored;
  }

  /** Claims {@code planId} for a fresh run id; empty when it cannot be run (spec §6.2). */
  public Optional<String> claim(String planId) {
    String runId = RunIds.next(services.clock());
    return store().consumePlan(planId, runId, services.clock().instant())
        ? Optional.of(runId)
        : Optional.empty();
  }

  /** The context every step of {@code runId} executes in. */
  public Context context(String runId) {
    return new Context(
        runId,
        services.home(),
        services.platform(),
        new CancellationToken(),
        Map.of(
            Services.class,
            services,
            StateStore.class,
            store(),
            Config.class,
            services.config(),
            SnapshotStore.class,
            new SnapshotStore(services.home(), services.platform().files(), services.clock()),
            KeyRing.class,
            new KeyRing(services.home()),
            JrsAdapter.class,
            new DeferredJrsAdapter(services.adapter()),
            Redactor.class,
            services.redactor(),
            SecretResolver.class,
            services.secrets()));
  }

  /** A runner that journals to the state store and reports to {@code sink}. */
  public Runner runner(EventSink sink) {
    return new Runner(store(), sink, services.clock(), Sleeper.system());
  }

  /**
   * Executes a fresh, claimed plan whose fingerprint the caller has already recomputed and compared
   * (runs recover rebuilds the stored plan before it calls this).
   */
  public RunOutcome run(Runner runner, Plan plan, Context ctx, RunOptions options) {
    return runner.run(plan, ctx, plan.fingerprint(), options);
  }

  /**
   * Executes a fresh, claimed plan against a fingerprint recomputed now (spec §6.2): the runner
   * refuses with {@code FingerprintMismatch} when the inputs changed between planning and the
   * operator's answer (assessment item E2).
   */
  public RunOutcome run(
      Runner runner, Plan plan, Context ctx, PlanFingerprint recomputed, RunOptions options) {
    return runner.run(plan, ctx, recomputed, options);
  }

  /** Continues a pending run from its interrupted step (spec §6.6). */
  public RunOutcome resume(Runner runner, Plan plan, String runId, Context ctx) {
    return new Recovery(store(), runner).resume(plan, runId, ctx, RunOptions.DEFAULT);
  }

  /** Compensates every succeeded step of a pending run (spec §6.6). */
  public RunOutcome rollback(Runner runner, Plan plan, String runId, Context ctx) {
    return new Recovery(store(), runner).rollback(plan, runId, ctx);
  }
}
