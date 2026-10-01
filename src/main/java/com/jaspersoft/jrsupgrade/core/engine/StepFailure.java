package com.jaspersoft.jrsupgrade.core.engine;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A step's own classification of why it failed (spec §6.1). The {@code Runner} never infers the
 * class from an exception type. Invariant: every failure names the cause, what it touched, where
 * the backups are, and the next action the operator can take, because every failure message must
 * contain those five things (spec §6.3).
 */
public sealed interface StepFailure
    permits StepFailure.Retryable, StepFailure.Recoverable, StepFailure.Fatal {

  String cause();

  List<Path> affectedPaths();

  List<URI> affectedUris();

  List<Path> backups();

  String nextAction();

  /**
   * Transient; the Runner applies the step's {@link RetryPolicy}. {@code retryAfter}, when the
   * server named a delay ({@code Retry-After}), is a floor under the policy's backoff.
   */
  record Retryable(
      String cause,
      List<Path> affectedPaths,
      List<URI> affectedUris,
      List<Path> backups,
      String nextAction,
      Optional<Duration> retryAfter)
      implements StepFailure {

    public Retryable {
      Objects.requireNonNull(retryAfter, "retryAfter");
    }

    public Retryable(
        String cause,
        List<Path> affectedPaths,
        List<URI> affectedUris,
        List<Path> backups,
        String nextAction) {
      this(cause, affectedPaths, affectedUris, backups, nextAction, Optional.empty());
    }
  }

  /** Permanent for this run; the Runner compensates back to the nearest phase boundary. */
  record Recoverable(
      String cause,
      List<Path> affectedPaths,
      List<URI> affectedUris,
      List<Path> backups,
      String nextAction)
      implements StepFailure {}

  /** The Runner must halt without compensating (compensation itself would be unsafe). */
  record Fatal(
      String cause,
      List<Path> affectedPaths,
      List<URI> affectedUris,
      List<Path> backups,
      String nextAction)
      implements StepFailure {}

  static Retryable retryable(String cause, String nextAction) {
    return new Retryable(cause, List.of(), List.of(), List.of(), nextAction);
  }

  static Recoverable recoverable(String cause, String nextAction) {
    return new Recoverable(cause, List.of(), List.of(), List.of(), nextAction);
  }

  static Fatal fatal(String cause, String nextAction) {
    return new Fatal(cause, List.of(), List.of(), List.of(), nextAction);
  }
}
