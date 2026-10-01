package com.jaspersoft.jrsupgrade.core.engine;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Single cancellation token shared by Ctrl-C and timeouts (spec §6.4). Invariant: once cancelled it
 * stays cancelled and records the first reason; steps poll {@link #isCancelled()} between units of
 * work and finish or compensate the unit in flight, never abandon a partial write.
 */
public final class CancellationToken {

  private final AtomicReference<String> reason = new AtomicReference<>();

  public void cancel(String why) {
    reason.compareAndSet(null, why == null ? "cancelled" : why);
  }

  public boolean isCancelled() {
    return reason.get() != null;
  }

  public String reason() {
    String r = reason.get();
    return r == null ? "" : r;
  }

  /** Throws {@link CancelledException} if cancellation was requested. */
  public void checkpoint() {
    if (isCancelled()) {
      throw new CancelledException(reason());
    }
  }

  /** Raised at a checkpoint; the Runner turns it into {@code RunCancelled}. */
  public static final class CancelledException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public CancelledException(String reason) {
      super(reason);
    }
  }
}
