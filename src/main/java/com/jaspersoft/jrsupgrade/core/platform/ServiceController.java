package com.jaspersoft.jrsupgrade.core.platform;

import java.time.Duration;
import java.util.function.BooleanSupplier;

/**
 * Starts and stops the JRS application server for the configured {@code service.kind} (spec §5.3).
 * Invariant: {@link #stop} and {@link #start} poll {@link #state()} until the transition completes
 * or the timeout elapses; they never assume the operation finished synchronously.
 */
public interface ServiceController {

  enum State {
    RUNNING,
    STOPPED,
    STARTING,
    STOPPING,
    UNKNOWN
  }

  State state();

  /** Returns the final state; {@code STOPPED} on success. Never throws for a slow service. */
  State stop(Duration timeout);

  State start(Duration timeout);

  /**
   * As {@link #stop(Duration)}, but gives up waiting as soon as {@code cancelled} answers true and
   * returns the last observed state. The default ignores the signal; real controllers override.
   */
  default State stop(Duration timeout, BooleanSupplier cancelled) {
    return stop(timeout);
  }

  default State start(Duration timeout, BooleanSupplier cancelled) {
    return start(timeout);
  }

  /** Human-readable description shown in plans, e.g. "Windows service jasperreportsTomcat". */
  String describe();
}
