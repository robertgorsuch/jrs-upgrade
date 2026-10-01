package com.jaspersoft.jrsupgrade.core.engine;

/**
 * Outcome of a precheck or postcheck (spec §6.1). Invariant: a {@link Fail} always carries a
 * remediation the operator can act on; checks never throw to signal failure.
 */
public sealed interface CheckResult permits CheckResult.Pass, CheckResult.Warn, CheckResult.Fail {

  record Pass() implements CheckResult {}

  record Warn(String message) implements CheckResult {}

  record Fail(String message, String remediation) implements CheckResult {}

  static CheckResult pass() {
    return new Pass();
  }

  static CheckResult warn(String message) {
    return new Warn(message);
  }

  static CheckResult fail(String message, String remediation) {
    return new Fail(message, remediation);
  }

  default boolean failed() {
    return this instanceof Fail;
  }
}
