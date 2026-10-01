package com.jaspersoft.jrsupgrade.core.engine;

import com.jaspersoft.jrsupgrade.core.event.EventSink;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Configurable {@link Step} for engine tests. Every execute/compensate call is appended to the
 * shared trace as {@code exec:<id>} / {@code comp:<id>} so tests can assert order.
 */
public final class FakeStep implements Step {

  private final String id;
  private final String phase;
  private final List<String> trace;
  private boolean mutating = true;
  private boolean irreversible = false;
  private boolean rollbackAllOnFailure = false;
  private RetryPolicy retryPolicy = RetryPolicy.NONE;
  private Function<Context, CheckResult> precheck = ctx -> CheckResult.pass();
  private Function<Context, CheckResult> postcheck = ctx -> CheckResult.pass();
  private BiFunction<Context, EventSink, StepResult> execute = (ctx, out) -> StepResult.ok();
  private BiFunction<Context, EventSink, StepResult> compensate = (ctx, out) -> StepResult.ok();

  public FakeStep(String id, String phase, List<String> trace) {
    this.id = id;
    this.phase = phase;
    this.trace = trace;
  }

  public FakeStep nonMutating() {
    this.mutating = false;
    return this;
  }

  public FakeStep markIrreversible() {
    this.irreversible = true;
    return this;
  }

  public FakeStep failureRollsBackAll() {
    this.rollbackAllOnFailure = true;
    return this;
  }

  @Override
  public boolean rollbackAllOnFailure() {
    return rollbackAllOnFailure;
  }

  public FakeStep retry(RetryPolicy policy) {
    this.retryPolicy = policy;
    return this;
  }

  public FakeStep precheck(Function<Context, CheckResult> fn) {
    this.precheck = fn;
    return this;
  }

  public FakeStep postcheck(Function<Context, CheckResult> fn) {
    this.postcheck = fn;
    return this;
  }

  public FakeStep onExecute(BiFunction<Context, EventSink, StepResult> fn) {
    this.execute = fn;
    return this;
  }

  public FakeStep onCompensate(BiFunction<Context, EventSink, StepResult> fn) {
    this.compensate = fn;
    return this;
  }

  /** Execute always returns the given result. */
  public FakeStep executeReturns(StepResult result) {
    return onExecute((ctx, out) -> result);
  }

  public FakeStep compensateReturns(StepResult result) {
    return onCompensate((ctx, out) -> result);
  }

  @Override
  public String id() {
    return id;
  }

  @Override
  public String title() {
    return "step " + id;
  }

  @Override
  public String phase() {
    return phase;
  }

  @Override
  public boolean irreversible() {
    return irreversible;
  }

  @Override
  public boolean mutating() {
    return mutating;
  }

  @Override
  public CheckResult precheck(Context ctx) {
    return precheck.apply(ctx);
  }

  @Override
  public StepResult execute(Context ctx, EventSink out) {
    trace.add("exec:" + id);
    return execute.apply(ctx, out);
  }

  @Override
  public CheckResult postcheck(Context ctx) {
    return postcheck.apply(ctx);
  }

  @Override
  public StepResult compensate(Context ctx, EventSink out) {
    trace.add("comp:" + id);
    return compensate.apply(ctx, out);
  }

  @Override
  public RetryPolicy retryPolicy() {
    return retryPolicy;
  }
}
