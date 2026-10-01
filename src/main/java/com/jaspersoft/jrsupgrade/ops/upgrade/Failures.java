package com.jaspersoft.jrsupgrade.ops.upgrade;

import com.jaspersoft.jrsupgrade.core.engine.StepFailure;
import com.jaspersoft.jrsupgrade.core.engine.StepResult;
import java.nio.file.Path;
import java.util.List;

/** Step failure constructors shared by the upgrade steps. */
final class Failures {

  private Failures() {}

  static StepResult recoverable(String cause, String nextAction) {
    return StepResult.failed(StepFailure.recoverable(cause, nextAction));
  }

  static StepResult recoverable(
      String cause, String nextAction, List<Path> affected, List<Path> backups) {
    return StepResult.failed(
        new StepFailure.Recoverable(
            cause, List.copyOf(affected), List.of(), List.copyOf(backups), nextAction));
  }

  static String describe(Exception e) {
    String msg = e.getMessage();
    return msg == null || msg.isBlank()
        ? e.getClass().getSimpleName()
        : e.getClass().getSimpleName() + ": " + msg;
  }
}
