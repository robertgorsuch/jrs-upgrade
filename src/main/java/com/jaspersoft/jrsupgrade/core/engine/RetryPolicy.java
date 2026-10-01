package com.jaspersoft.jrsupgrade.core.engine;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Bounded exponential backoff (spec §6.5). Invariant: {@code maxAttempts >= 1}; {@link #NONE} makes
 * exactly one attempt. Delay for attempt {@code n} (1-based, n &gt;= 2) is {@code min(base *
 * factor^(n-2), max)} with symmetric jitter.
 */
public record RetryPolicy(
    int maxAttempts, Duration base, double factor, Duration max, double jitterFraction) {

  public static final RetryPolicy NONE = new RetryPolicy(1, Duration.ZERO, 1.0, Duration.ZERO, 0.0);

  /** Spec default for HTTP steps: 5 attempts, base 2s, factor 2, max 60s, jitter 20%. */
  public static final RetryPolicy HTTP_DEFAULT =
      new RetryPolicy(5, Duration.ofSeconds(2), 2.0, Duration.ofSeconds(60), 0.20);

  public RetryPolicy {
    if (maxAttempts < 1) {
      throw new IllegalArgumentException("maxAttempts must be >= 1");
    }
    if (jitterFraction < 0 || jitterFraction > 1) {
      throw new IllegalArgumentException("jitterFraction must be within [0,1]");
    }
  }

  /** Delay before the given attempt number (2-based; attempt 1 has no delay). */
  public Duration delayBefore(int attempt) {
    if (attempt <= 1 || maxAttempts == 1) {
      return Duration.ZERO;
    }
    double raw = (double) base.toMillis() * Math.pow(factor, attempt - 2);
    long capped = (long) Math.min(raw, (double) max.toMillis());
    if (jitterFraction == 0 || capped == 0) {
      return Duration.ofMillis(capped);
    }
    long spread = (long) ((double) capped * jitterFraction);
    long jitter = ThreadLocalRandom.current().nextLong(-spread, spread + 1);
    return Duration.ofMillis(Math.max(0, capped + jitter));
  }
}
