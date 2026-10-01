package com.jaspersoft.jrsupgrade.core.engine;

/**
 * States a step passes through during a run; each change is journalled to {@code step_transitions}
 * before the matching event is emitted (spec §6.3). Invariant: {@code SUCCEEDED} is the only state
 * from which compensation starts, and {@code ROLLED_BACK}, {@code ROLLBACK_FAILED} and {@code
 * SKIPPED} are terminal for the step.
 */
public enum StepState {
  PENDING,
  RUNNING,
  SUCCEEDED,
  FAILED,
  ROLLED_BACK,
  ROLLBACK_FAILED,
  SKIPPED
}
