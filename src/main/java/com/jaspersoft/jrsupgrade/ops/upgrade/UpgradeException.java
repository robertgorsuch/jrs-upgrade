package com.jaspersoft.jrsupgrade.ops.upgrade;

import java.util.Objects;

/**
 * Planning-time refusal of an upgrade or rollback. Invariant: nothing has been mutated when it is
 * thrown; {@link #exitCode()} is the spec §18 code the CLI should exit with (2 for a precheck
 * failure, 6 for an unsupported upgrade path) and {@link #remediation()} tells the operator what to
 * change.
 */
public final class UpgradeException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public static final int USAGE = 1;
  public static final int PRECHECK = 2;
  public static final int UNSUPPORTED = 6;

  private final int exitCode;
  private final String remediation;

  public UpgradeException(int exitCode, String message, String remediation) {
    super(message);
    this.exitCode = exitCode;
    this.remediation = Objects.requireNonNull(remediation, "remediation");
  }

  public UpgradeException(int exitCode, String message, String remediation, Throwable cause) {
    super(message, cause);
    this.exitCode = exitCode;
    this.remediation = Objects.requireNonNull(remediation, "remediation");
  }

  public int exitCode() {
    return exitCode;
  }

  public String remediation() {
    return remediation;
  }
}
