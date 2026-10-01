package com.jaspersoft.jrsupgrade.ops.customizations;

import java.util.Objects;

/**
 * Refusal of a customizations command. Invariant: nothing was mutated when it is thrown; the
 * message names the problem and {@link #remediation()} what the operator can do about it. The CLI
 * maps it to exit code 2.
 */
public final class CustomizationException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String remediation;

  public CustomizationException(String message, String remediation) {
    super(message);
    this.remediation = Objects.requireNonNull(remediation, "remediation");
  }

  public CustomizationException(String message, String remediation, Throwable cause) {
    super(message, cause);
    this.remediation = Objects.requireNonNull(remediation, "remediation");
  }

  public String remediation() {
    return remediation;
  }
}
