package com.jaspersoft.jrsupgrade.core.redact;

import com.jaspersoft.jrsupgrade.core.engine.StepFailure;
import com.jaspersoft.jrsupgrade.core.event.Event;
import com.jaspersoft.jrsupgrade.core.event.EventSink;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Objects;

/**
 * An {@link EventSink} decorator that redacts every string-bearing field of every {@link Event}
 * variant before handing it to the delegate (spec §5.8). Invariant: the switch over the sealed
 * hierarchy has no default branch, so adding an event type without deciding what to redact fails to
 * compile; identifiers (run id, step id, phase, plan id, fingerprint) and paths pass through
 * unchanged because they never carry secrets, while URIs keep only scheme, host, port, path and
 * fragment. {@link com.jaspersoft.jrsupgrade.core.engine.Runner} wraps its sink in this decorator,
 * so every subscriber receives redacted events without having to redact for itself.
 */
public final class RedactingEventSink implements EventSink {

  private final EventSink delegate;
  private final Redactor redactor;

  public RedactingEventSink(EventSink delegate, Redactor redactor) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
    this.redactor = Objects.requireNonNull(redactor, "redactor");
  }

  @Override
  public void emit(Event event) {
    delegate.emit(redact(event));
  }

  /** The event with every free-text field passed through the redactor. */
  public Event redact(Event event) {
    Objects.requireNonNull(event, "event");
    return switch (event) {
      case Event.PlanCreated e -> e;
      case Event.StepPending e ->
          new Event.StepPending(e.ts(), e.runId(), e.stepId(), e.phase(), r(e.title()));
      case Event.StepRunning e ->
          new Event.StepRunning(e.ts(), e.runId(), e.stepId(), e.phase(), r(e.title()));
      case Event.StepRetry e ->
          new Event.StepRetry(
              e.ts(),
              e.runId(),
              e.stepId(),
              e.phase(),
              e.attempt(),
              e.maxAttempts(),
              e.delayMillis(),
              r(e.cause()));
      case Event.StepSucceeded e -> e;
      case Event.StepFailed e ->
          new Event.StepFailed(e.ts(), e.runId(), e.stepId(), e.phase(), redact(e.failure()));
      case Event.StepSkipped e ->
          new Event.StepSkipped(e.ts(), e.runId(), e.stepId(), e.phase(), r(e.reason()));
      case Event.StepRolledBack e -> e;
      case Event.StepRollbackFailed e ->
          new Event.StepRollbackFailed(
              e.ts(), e.runId(), e.stepId(), e.phase(), r(e.cause()), e.backups());
      case Event.Log e ->
          new Event.Log(e.ts(), e.runId(), e.stepId(), e.phase(), e.level(), r(e.message()));
      case Event.RunSucceeded e -> e;
      case Event.RunFailed e ->
          new Event.RunFailed(
              e.ts(),
              e.runId(),
              e.phase(),
              r(e.cause()),
              e.backups(),
              r(e.nextAction()),
              e.rollbackIncomplete());
      case Event.RunCancelled e ->
          new Event.RunCancelled(e.ts(), e.runId(), e.phase(), r(e.detail()));
      case Event.RunRolledBack e ->
          new Event.RunRolledBack(
              e.ts(), e.runId(), e.phase(), e.rolledBackToPhase(), r(e.cause()));
    };
  }

  /**
   * The failure with its cause and next action redacted; paths pass through, and every URI loses
   * its user-info and query, the two parts that can carry a credential or a token.
   */
  public StepFailure redact(StepFailure failure) {
    Objects.requireNonNull(failure, "failure");
    return switch (failure) {
      case StepFailure.Retryable f ->
          new StepFailure.Retryable(
              r(f.cause()),
              f.affectedPaths(),
              strip(f.affectedUris()),
              f.backups(),
              r(f.nextAction()),
              f.retryAfter());
      case StepFailure.Recoverable f ->
          new StepFailure.Recoverable(
              r(f.cause()),
              f.affectedPaths(),
              strip(f.affectedUris()),
              f.backups(),
              r(f.nextAction()));
      case StepFailure.Fatal f ->
          new StepFailure.Fatal(
              r(f.cause()),
              f.affectedPaths(),
              strip(f.affectedUris()),
              f.backups(),
              r(f.nextAction()));
    };
  }

  private static List<URI> strip(List<URI> uris) {
    return uris.stream().map(RedactingEventSink::strip).toList();
  }

  /** Scheme, host, port, path and fragment only; user-info and query are dropped. */
  static URI strip(URI uri) {
    if (uri.isOpaque() || (uri.getRawUserInfo() == null && uri.getRawQuery() == null)) {
      return uri;
    }
    try {
      return new URI(
          uri.getScheme(),
          null,
          uri.getHost(),
          uri.getPort(),
          uri.getPath(),
          null,
          uri.getFragment());
    } catch (URISyntaxException e) {
      // The components came out of a valid URI, so this cannot happen; keep only the path if it
      // does.
      return URI.create(uri.getRawPath() == null ? "" : uri.getRawPath());
    }
  }

  private String r(String text) {
    return redactor.redact(text);
  }
}
