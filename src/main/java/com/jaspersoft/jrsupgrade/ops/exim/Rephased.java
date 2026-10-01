package com.jaspersoft.jrsupgrade.ops.exim;

import com.jaspersoft.jrsupgrade.core.engine.CheckResult;
import com.jaspersoft.jrsupgrade.core.engine.Context;
import com.jaspersoft.jrsupgrade.core.engine.RetryPolicy;
import com.jaspersoft.jrsupgrade.core.engine.Step;
import com.jaspersoft.jrsupgrade.core.engine.StepResult;
import com.jaspersoft.jrsupgrade.core.event.EventSink;
import java.util.Objects;

/**
 * A strategy step moved into another phase of a composed plan. Invariants: only {@link #phase()}
 * and {@link #id()} (prefixed with the new phase so ids stay unique when the same strategy
 * contributes an export and an import to one plan) differ from the delegate; every check,
 * execution, compensation and retry policy is the delegate's, so idempotency and rollback
 * guarantees carry over unchanged.
 */
final class Rephased implements Step {

  private final Step delegate;
  private final String phase;

  private Rephased(String phase, Step delegate) {
    this.phase = Objects.requireNonNull(phase, "phase");
    this.delegate = Objects.requireNonNull(delegate, "delegate");
  }

  static Step into(String phase, Step delegate) {
    return new Rephased(phase, delegate);
  }

  Step delegate() {
    return delegate;
  }

  @Override
  public String id() {
    return phase + "." + delegate.id();
  }

  @Override
  public String title() {
    return delegate.title();
  }

  @Override
  public String phase() {
    return phase;
  }

  @Override
  public String detail() {
    return delegate.detail();
  }

  @Override
  public boolean irreversible() {
    return delegate.irreversible();
  }

  @Override
  public boolean mutating() {
    return delegate.mutating();
  }

  @Override
  public CheckResult precheck(Context ctx) {
    return delegate.precheck(ctx);
  }

  @Override
  public StepResult execute(Context ctx, EventSink out) {
    return delegate.execute(ctx, out);
  }

  @Override
  public CheckResult postcheck(Context ctx) {
    return delegate.postcheck(ctx);
  }

  @Override
  public StepResult compensate(Context ctx, EventSink out) {
    return delegate.compensate(ctx, out);
  }

  @Override
  public RetryPolicy retryPolicy() {
    return delegate.retryPolicy();
  }
}
