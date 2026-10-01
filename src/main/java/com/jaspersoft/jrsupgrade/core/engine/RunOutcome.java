package com.jaspersoft.jrsupgrade.core.engine;

import java.nio.file.Path;
import java.util.List;

/**
 * How a run ended, as the Runner reports it to the CLI (spec §6.3, §18). Invariant: {@link
 * #exitCode()} is the process exit code for the outcome: 0 success, 3 rolled back cleanly, 4
 * rollback incomplete, 2 when nothing was mutated (precheck failure, fingerprint mismatch, or a
 * fatal failure before any mutation), 5 cancelled. Lists are immutable copies.
 */
public sealed interface RunOutcome
    permits RunOutcome.Succeeded,
        RunOutcome.RolledBack,
        RunOutcome.FingerprintMismatch,
        RunOutcome.PrecheckFailed,
        RunOutcome.Failed,
        RunOutcome.Cancelled {

  /** Exit code per spec §18. */
  default int exitCode() {
    return switch (this) {
      case Succeeded s -> 0;
      case RolledBack r -> 3;
      case Failed f -> f.rollbackIncomplete() ? 4 : 2;
      case Cancelled c -> 5;
      case PrecheckFailed p -> 2;
      case FingerprintMismatch m -> 2;
    };
  }

  record Succeeded() implements RunOutcome {}

  /** Every succeeded step was compensated back to the start of {@code rolledBackToPhase}. */
  record RolledBack(String rolledBackToPhase, String cause) implements RunOutcome {}

  /** The run stopped without a clean rollback; {@code nextAction} tells the operator what to do. */
  record Failed(String cause, boolean rollbackIncomplete, String nextAction, List<Path> backups)
      implements RunOutcome {
    public Failed {
      backups = List.copyOf(backups);
    }
  }

  record Cancelled(String reason) implements RunOutcome {}

  /** A precheck failed before any mutation; nothing was changed. */
  record PrecheckFailed(String stepId, String message, String remediation) implements RunOutcome {}

  /** The recomputed fingerprint differs from the plan's; nothing was changed. */
  record FingerprintMismatch(List<String> changedKeys) implements RunOutcome {
    public FingerprintMismatch {
      changedKeys = List.copyOf(changedKeys);
    }
  }
}
