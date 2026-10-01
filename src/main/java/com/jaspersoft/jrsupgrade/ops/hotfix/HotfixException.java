package com.jaspersoft.jrsupgrade.ops.hotfix;

import java.util.Objects;

/**
 * Refusal raised before any Plan exists: unreadable bundle, invalid manifest, untrusted signature,
 * unknown hotfix id, or a rollback blocked by later hotfixes. Invariants: nothing has been mutated
 * when it is thrown; {@link #exitCode()} is the spec §18 code the CLI should exit with (2 for
 * precheck-class refusals, 7 for signature and hash failures); {@link #remediation()} tells the
 * operator what to change; the message never carries secrets.
 */
public final class HotfixException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Precheck-class refusal (spec §18 exit 2). */
  public static final int PRECHECK = 2;

  /** Signature or hash failure (spec §18 exit 7). */
  public static final int SIGNATURE = 7;

  private final int exitCode;
  private final String remediation;

  public HotfixException(int exitCode, String message, String remediation) {
    super(message);
    this.exitCode = exitCode;
    this.remediation = Objects.requireNonNull(remediation, "remediation");
  }

  public HotfixException(int exitCode, String message, String remediation, Throwable cause) {
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
