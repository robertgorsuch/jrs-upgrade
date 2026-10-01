package com.jaspersoft.jrsupgrade.core.platform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/**
 * Identifies the Linux service manager from process 1 and the init directories, so a host that is
 * not running systemd is named instead of being silently driven with {@code catalina.sh} (review
 * 3.2). Invariant: detection is read-only and never throws; an unreadable {@code /proc/1/comm} is
 * reported as {@link Kind#UNKNOWN} rather than assumed to be systemd, and a supervised Tomcat
 * (supervisord, OpenRC, SysV) is reported under its own kind so {@code init} and {@code doctor} can
 * warn that stopping the script bypasses the supervisor, which may respawn the server mid-run.
 */
public final class LinuxInit {

  /** Which process-1 supervisor is running. */
  public enum Kind {
    SYSTEMD,
    SYSV,
    OPENRC,
    SUPERVISOR,
    OTHER,
    UNKNOWN
  }

  /** What was detected: the kind, the process-1 name if it could be read, and a summary line. */
  public record Detected(Kind kind, Optional<String> pid1, String detail) {

    /** True when jrs-upgrade can drive the service through {@code systemctl}. */
    public boolean systemd() {
      return kind == Kind.SYSTEMD;
    }

    /**
     * True when process 1 supervises services some other way, so a script stop can be undone by the
     * supervisor restarting the server.
     */
    public boolean supervised() {
      return kind == Kind.SYSV || kind == Kind.OPENRC || kind == Kind.SUPERVISOR;
    }
  }

  private static final Path PROC_1_COMM = Path.of("/proc/1/comm");
  private static final Path ETC_INIT_D = Path.of("/etc/init.d");
  private static final Path RUN_OPENRC = Path.of("/run/openrc");

  private LinuxInit() {}

  /** Detection against the real {@code /proc} and {@code /etc} of this host. */
  public static Detected detect() {
    return detect(PROC_1_COMM, ETC_INIT_D, RUN_OPENRC);
  }

  /** Detection against the given paths, so tests need no {@code /proc}. */
  public static Detected detect(Path proc1Comm, Path etcInitD, Path runOpenrc) {
    Optional<String> pid1 = readComm(proc1Comm);
    boolean initD = Files.isDirectory(etcInitD);
    boolean openrc = Files.exists(runOpenrc);
    if (pid1.isEmpty()) {
      return new Detected(
          Kind.UNKNOWN,
          Optional.empty(),
          "cannot read " + proc1Comm + "; no service manager detected");
    }
    String name = pid1.get();
    String lower = name.toLowerCase(Locale.ROOT);
    if (lower.equals("systemd")) {
      return new Detected(Kind.SYSTEMD, pid1, "systemd is process 1");
    }
    if (lower.contains("supervisord")) {
      return new Detected(
          Kind.SUPERVISOR,
          pid1,
          "supervisord is process 1; it may restart a server jrs-upgrade stopped");
    }
    if (openrc || lower.equals("openrc-init")) {
      return new Detected(
          Kind.OPENRC, pid1, "OpenRC is process 1 (" + name + "); systemctl is not available");
    }
    if (initD) {
      return new Detected(
          Kind.SYSV,
          pid1,
          "process 1 is " + name + " with " + etcInitD + " scripts; systemctl is not available");
    }
    return new Detected(
        Kind.OTHER,
        pid1,
        "process 1 is " + name + "; no service manager detected (no systemd, no " + etcInitD + ")");
  }

  private static Optional<String> readComm(Path proc1Comm) {
    try {
      String text = Files.readString(proc1Comm, StandardCharsets.UTF_8).strip();
      return text.isEmpty() ? Optional.empty() : Optional.of(text);
    } catch (IOException | RuntimeException e) {
      return Optional.empty();
    }
  }
}
