package com.jaspersoft.jrsupgrade.core.config;

import java.util.List;
import java.util.Objects;

/**
 * The configuration is missing, malformed or fails schema validation (spec §5.1). Invariants: the
 * message lists <em>every</em> violation as {@code path: message} so the operator fixes them in one
 * pass, and {@link #remediation()} always names the next action; secret values never appear here
 * because only references, never resolved secrets, live in the configuration.
 */
public final class ConfigException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final List<String> violations;
  private final String remediation;

  public ConfigException(String message, String remediation) {
    this(message, List.of(), remediation);
  }

  public ConfigException(String message, List<String> violations, String remediation) {
    super(compose(message, violations, remediation));
    this.violations = List.copyOf(violations);
    this.remediation = Objects.requireNonNull(remediation, "remediation");
  }

  /** Builds the exception for a non-empty list of {@code path: message} schema violations. */
  public static ConfigException violations(List<String> violations) {
    return new ConfigException(
        "configuration is invalid",
        violations,
        "fix the listed keys in config.yaml or the overriding JRS_UPGRADE_* variable or flag");
  }

  /** Each entry is {@code path: message}; empty unless this is a validation failure. */
  public List<String> violations() {
    return violations;
  }

  public String remediation() {
    return remediation;
  }

  private static String compose(String message, List<String> violations, String remediation) {
    StringBuilder sb = new StringBuilder(message);
    for (String v : violations) {
      sb.append(System.lineSeparator()).append("  ").append(v);
    }
    if (!remediation.isEmpty()) {
      sb.append(violations.isEmpty() ? "; " : System.lineSeparator()).append(remediation);
    }
    return sb.toString();
  }
}
