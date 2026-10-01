package com.jaspersoft.jrsupgrade.core.engine;

/** Result of {@code Step.execute} or {@code Step.compensate}. */
public sealed interface StepResult permits StepResult.Ok, StepResult.Failed {

  record Ok() implements StepResult {}

  record Failed(StepFailure failure) implements StepResult {}

  static StepResult ok() {
    return new Ok();
  }

  static StepResult failed(StepFailure failure) {
    return new Failed(failure);
  }
}
