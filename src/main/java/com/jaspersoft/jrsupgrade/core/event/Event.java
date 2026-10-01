package com.jaspersoft.jrsupgrade.core.event;

import com.jaspersoft.jrsupgrade.core.engine.StepFailure;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Typed, sealed event hierarchy shared by the CLI renderer and {@code --json} output (spec §5.9).
 * Invariant: every event carries the run id and phase it belongs to, and payloads are typed
 * records, never free-form maps, so the JSON schema in Phase 8 can validate them. Messages must
 * already be redacted by the emitter; consumers never see raw secrets.
 */
public sealed interface Event
    permits Event.PlanCreated,
        Event.StepPending,
        Event.StepRunning,
        Event.StepRetry,
        Event.StepSucceeded,
        Event.StepFailed,
        Event.StepSkipped,
        Event.StepRolledBack,
        Event.StepRollbackFailed,
        Event.Log,
        Event.RunSucceeded,
        Event.RunFailed,
        Event.RunCancelled,
        Event.RunRolledBack {

  Instant ts();

  String runId();

  Optional<String> stepId();

  /** Phase name of the step, or {@code "run"} for run-level events. */
  String phase();

  /** Short discriminator used as the JSON {@code type} field and the SSE event name. */
  default String type() {
    return getClass().getSimpleName();
  }

  record PlanCreated(Instant ts, String runId, String phase, String planId, String fingerprint)
      implements Event {
    @Override
    public Optional<String> stepId() {
      return Optional.empty();
    }
  }

  record StepPending(Instant ts, String runId, Optional<String> stepId, String phase, String title)
      implements Event {}

  record StepRunning(Instant ts, String runId, Optional<String> stepId, String phase, String title)
      implements Event {}

  record StepRetry(
      Instant ts,
      String runId,
      Optional<String> stepId,
      String phase,
      int attempt,
      int maxAttempts,
      long delayMillis,
      String cause)
      implements Event {}

  record StepSucceeded(
      Instant ts, String runId, Optional<String> stepId, String phase, long elapsedMillis)
      implements Event {}

  record StepFailed(
      Instant ts, String runId, Optional<String> stepId, String phase, StepFailure failure)
      implements Event {}

  record StepSkipped(Instant ts, String runId, Optional<String> stepId, String phase, String reason)
      implements Event {}

  record StepRolledBack(
      Instant ts, String runId, Optional<String> stepId, String phase, long elapsedMillis)
      implements Event {}

  record StepRollbackFailed(
      Instant ts,
      String runId,
      Optional<String> stepId,
      String phase,
      String cause,
      List<Path> backups)
      implements Event {}

  /** Free-text progress line (already redacted), e.g. streamed vendor-tool output. */
  record Log(
      Instant ts, String runId, Optional<String> stepId, String phase, Level level, String message)
      implements Event {
    public enum Level {
      DEBUG,
      INFO,
      WARN,
      ERROR
    }
  }

  record RunSucceeded(Instant ts, String runId, String phase, long elapsedMillis) implements Event {
    @Override
    public Optional<String> stepId() {
      return Optional.empty();
    }
  }

  /**
   * Terminal failure without a clean rollback; {@code nextAction} tells the operator what to do.
   */
  record RunFailed(
      Instant ts,
      String runId,
      String phase,
      String cause,
      List<Path> backups,
      String nextAction,
      boolean rollbackIncomplete)
      implements Event {
    @Override
    public Optional<String> stepId() {
      return Optional.empty();
    }
  }

  record RunCancelled(Instant ts, String runId, String phase, String detail) implements Event {
    @Override
    public Optional<String> stepId() {
      return Optional.empty();
    }
  }

  /** Run failed but every succeeded step was compensated back to {@code rolledBackToPhase}. */
  record RunRolledBack(
      Instant ts, String runId, String phase, String rolledBackToPhase, String cause)
      implements Event {
    @Override
    public Optional<String> stepId() {
      return Optional.empty();
    }
  }
}
