package com.jaspersoft.jrsupgrade.core.platform;

import java.util.List;
import java.util.Objects;

/**
 * The platform refused or could not run a service-control command ({@code sc.exe}, {@code
 * systemctl}, ...), so waiting for the service to change state would only burn the timeout.
 * Invariants: the message names the command, its exit code, the last lines it printed and the
 * remediation (usually: run jrs-upgrade with the rights the service manager demands); it never
 * carries a secret because service commands take none.
 */
public final class ServiceControlException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final List<String> command;
  private final int exitCode;

  public ServiceControlException(List<String> command, int exitCode, String message) {
    super(message);
    this.command = List.copyOf(Objects.requireNonNull(command, "command"));
    this.exitCode = exitCode;
  }

  public List<String> command() {
    return command;
  }

  /** The command's exit code, or -1 when it could not be started. */
  public int exitCode() {
    return exitCode;
  }
}
