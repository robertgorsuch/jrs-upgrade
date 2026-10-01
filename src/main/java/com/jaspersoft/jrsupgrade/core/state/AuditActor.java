package com.jaspersoft.jrsupgrade.core.state;

/**
 * The actor every audit row names: the operating-system account running jrs-upgrade. Invariant: the
 * command layer and the plans it runs use this one value, so the rows one command writes (a
 * confirmation before planning and the steps of the run) name the same actor (#159). Never empty.
 */
public final class AuditActor {

  private AuditActor() {}

  /** The account name, or {@code unknown} when the runtime does not report one. */
  public static String current() {
    String name = System.getProperty("user.name", "");
    return name.isBlank() ? "unknown" : name;
  }
}
