package com.jaspersoft.jrsupgrade.app;

import com.jaspersoft.jrsupgrade.core.engine.CancellationToken;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;

/**
 * What the JVM shutdown hook does when the operator interrupts a run (spec §6.5, review finding
 * 4.3). Invariants: the run is cancelled through its single {@link CancellationToken}; the hook
 * then waits, bounded, for the worker to finish or compensate the step in flight and for the main
 * thread to write the outcome block; and the process ends through {@code halt} with the run's exit
 * code (5 for a cancelled run) rather than the JVM's 130 or 143 with the output cut mid-line.
 * {@code halt} is the only way out because {@code System.exit} blocks once shutdown has begun.
 */
final class RunGuard {

  static final Duration RENDER_GRACE = Duration.ofSeconds(5);

  private final CancellationToken cancel;
  private final Thread worker;
  private final Duration workerGrace;
  private final Duration renderGrace;
  private final IntConsumer halt;
  private final CountDownLatch rendered = new CountDownLatch(1);
  private volatile int exitCode = ExitCodes.CANCELLED;

  RunGuard(
      CancellationToken cancel,
      Thread worker,
      Duration workerGrace,
      Duration renderGrace,
      IntConsumer halt) {
    this.cancel = Objects.requireNonNull(cancel, "cancel");
    this.worker = Objects.requireNonNull(worker, "worker");
    this.workerGrace = Objects.requireNonNull(workerGrace, "workerGrace");
    this.renderGrace = Objects.requireNonNull(renderGrace, "renderGrace");
    this.halt = Objects.requireNonNull(halt, "halt");
  }

  /** The main thread reports that the outcome is written and what the exit code is. */
  void rendered(int code) {
    exitCode = code;
    rendered.countDown();
  }

  boolean wasRendered() {
    return rendered.getCount() == 0;
  }

  /** Runs in the shutdown hook: cancel, wait for the worker, wait for the output, halt. */
  void onShutdown() {
    cancel.cancel("interrupted");
    try {
      worker.join(workerGrace.toMillis());
      rendered.await(renderGrace.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    halt.accept(exitCode);
  }
}
