package com.jaspersoft.jrsupgrade.jrs.strategy;

import com.jaspersoft.jrsupgrade.core.engine.StepFailure;
import com.jaspersoft.jrsupgrade.core.engine.StepResult;
import com.jaspersoft.jrsupgrade.jrs.rest.RestException;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;

/** Shorthand for the {@link StepFailure}s the strategy steps produce. */
final class Failures {

  private Failures() {}

  static StepResult recoverable(String cause, List<Path> paths, String nextAction) {
    return StepResult.failed(
        new StepFailure.Recoverable(cause, List.copyOf(paths), List.of(), List.of(), nextAction));
  }

  static StepResult recoverable(String cause, List<Path> paths, List<URI> uris, String nextAction) {
    return StepResult.failed(
        new StepFailure.Recoverable(
            cause, List.copyOf(paths), List.copyOf(uris), List.of(), nextAction));
  }

  static StepResult recoverableWithBackups(
      String cause, List<Path> paths, List<Path> backups, String nextAction) {
    return StepResult.failed(
        new StepFailure.Recoverable(
            cause, List.copyOf(paths), List.of(), List.copyOf(backups), nextAction));
  }

  /**
   * A failure the runner must not compensate: the server may still be executing the task, so
   * re-importing the pre-import snapshot now would race it (assessment item U3).
   */
  static StepResult fatal(String cause, String nextAction) {
    return StepResult.failed(StepFailure.fatal(cause, nextAction));
  }

  static StepResult retryable(String cause, List<URI> uris, String nextAction) {
    return StepResult.failed(
        new StepFailure.Retryable(cause, List.of(), List.copyOf(uris), List.of(), nextAction));
  }

  /** A transient HTTP answer (review finding 2.2): retried, no sooner than {@code Retry-After}. */
  static StepResult transientHttp(RestException e, String doing, String remediation) {
    return StepResult.failed(
        new StepFailure.Retryable(
            doing + ": HTTP " + e.status() + " (" + e.getMessage() + ")",
            List.of(),
            List.of(),
            List.of(),
            remediation,
            e.retryAfter()));
  }

  static final String TRANSIENT_REMEDIATION =
      "the server or a proxy in front of it answered a transient status; the step is retried and"
          + " no action is needed unless every retry fails";

  static String describe(RuntimeException e) {
    String msg = e.getMessage();
    return msg == null || msg.isBlank()
        ? e.getClass().getSimpleName()
        : e.getClass().getSimpleName() + ": " + msg;
  }
}
