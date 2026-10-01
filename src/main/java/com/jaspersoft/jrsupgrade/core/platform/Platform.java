package com.jaspersoft.jrsupgrade.core.platform;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * OS abstraction (spec §5.3). All process, service and file-system side effects go through this
 * interface so ops code is platform-neutral and tests can substitute fakes. Invariants: no shell is
 * ever invoked, arguments are passed as lists; file operations preserve ownership and ACLs; all I/O
 * streams and never loads a whole file into memory.
 */
public interface Platform {

  OsFamily os();

  Arch arch();

  ServiceController services(ServiceConfig cfg);

  FileOps files();

  ProcessRunner processes();

  /**
   * Default {@code $JRS_UPGRADE_HOME}: the system location (ProgramData on Windows, /var/lib on
   * Linux) when it exists or can be created, else the per-user {@code ~/.jrs-upgrade}. Callers
   * resolve the home through {@code JrsUpgradeHomeResolver}, which refuses (exit 2) when the system
   * home exists but this user cannot write to it, rather than quietly starting a second journal
   * beside someone else's; this method alone does not make that check.
   */
  Path defaultHome();

  /**
   * Inspects an install dir for the Tomcat layout; empty when it does not look like a JRS install.
   */
  Optional<TomcatLayout> detectTomcat(Path installDir);

  /** Places {@code init} should look for an installation, most likely first. */
  List<Path> candidateInstallDirs();

  /**
   * {@link #candidateInstallDirs()} with which of them a running Tomcat pointed at and what the
   * process scan could not see. The default knows of no running Tomcat, which is what test fakes
   * without process scanning need.
   */
  default InstallScan scanInstallDirs() {
    return new InstallScan(candidateInstallDirs(), Set.of(), Optional.empty());
  }

  /**
   * A platform whose process-based service controllers ({@code manual}, and {@code systemd}'s
   * lingering-JVM check) judge the Tomcat under {@code installDir} rather than any Tomcat on the
   * host. The real platforms return a copy; the default returns this platform unchanged, which is
   * what test fakes without process scanning need.
   */
  default Platform withInstallDir(Path installDir) {
    return this;
  }

  /**
   * The Tomcat JVMs running under {@code dir} now, whatever {@code service.kind} says (issue #147).
   * The default does not scan processes and says so, which is what test fakes need.
   */
  default RunningTomcats runningTomcats(Path dir) {
    return new RunningTomcats.Unavailable("this platform does not scan processes");
  }

  enum OsFamily {
    WINDOWS,
    LINUX
  }

  enum Arch {
    X86_64,
    OTHER
  }
}
