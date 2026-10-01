package com.jaspersoft.jrsupgrade.ops;

import java.util.Objects;

/**
 * One line of a diagnostic report shared by {@code init}, {@code doctor} and {@code smoke} (spec
 * §12). Invariants: {@code detail} is a single line that never contains a secret value (only
 * references, paths and names); {@code remediation} is empty for {@link Status#PASS} and names the
 * next operator action for every other status; {@code name} is stable so {@code --json} consumers
 * can key on it.
 */
public record ReportItem(String name, Status status, String detail, String remediation) {

  /** Outcome of a check; {@link #rank()} orders FAIL first and SKIP last in reports. */
  public enum Status {
    FAIL(0),
    WARN(1),
    PASS(2),
    SKIP(3);

    private final int rank;

    Status(int rank) {
      this.rank = rank;
    }

    public int rank() {
      return rank;
    }
  }

  public ReportItem {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(detail, "detail");
    Objects.requireNonNull(remediation, "remediation");
  }

  public static ReportItem pass(String name, String detail) {
    return new ReportItem(name, Status.PASS, detail, "");
  }

  public static ReportItem warn(String name, String detail, String remediation) {
    return new ReportItem(name, Status.WARN, detail, remediation);
  }

  public static ReportItem fail(String name, String detail, String remediation) {
    return new ReportItem(name, Status.FAIL, detail, remediation);
  }

  public static ReportItem skip(String name, String detail, String remediation) {
    return new ReportItem(name, Status.SKIP, detail, remediation);
  }
}
