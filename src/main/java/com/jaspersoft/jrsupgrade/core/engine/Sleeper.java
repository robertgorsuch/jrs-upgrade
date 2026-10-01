package com.jaspersoft.jrsupgrade.core.engine;

import java.time.Duration;

/**
 * How the Runner waits between retry attempts (spec §6.5), abstracted so tests never sleep.
 * Invariants: an interrupted wait restores the interrupt flag and surfaces as {@link
 * CancellationToken.CancelledException}, which the Runner treats like any other cancellation; a
 * wait given a {@link CancellationToken} ends within one {@link #SLICE} of the token being
 * cancelled, by the same exception, so a long backoff never hides Ctrl-C.
 */
@FunctionalInterface
public interface Sleeper {

  /** How often a token-aware wait looks at the token. */
  Duration SLICE = Duration.ofMillis(200);

  void sleep(Duration duration);

  /**
   * Sleeps {@code duration} in slices of at most {@link #SLICE}, checking {@code token} before each
   * slice and once more at the end, so cancellation is noticed while waiting rather than only after
   * the whole backoff and another attempt.
   */
  default void sleep(Duration duration, CancellationToken token) {
    Duration left = duration;
    while (left.compareTo(Duration.ZERO) > 0) {
      token.checkpoint();
      Duration slice = left.compareTo(SLICE) < 0 ? left : SLICE;
      sleep(slice);
      left = left.minus(slice);
    }
    token.checkpoint();
  }

  /** Real wall-clock sleeping. */
  static Sleeper system() {
    return duration -> {
      if (duration.isZero() || duration.isNegative()) {
        return;
      }
      try {
        Thread.sleep(duration);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new CancellationToken.CancelledException("interrupted while waiting to retry");
      }
    };
  }

  /** Never waits; for tests. Still observes the token, so a cancelled run ends at the wait. */
  static Sleeper none() {
    return new Sleeper() {
      @Override
      public void sleep(Duration duration) {}

      @Override
      public void sleep(Duration duration, CancellationToken token) {
        token.checkpoint();
      }
    };
  }
}
