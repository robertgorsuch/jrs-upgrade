package com.jaspersoft.jrsupgrade.core.platform;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Finds running Tomcat JVMs on this machine. Invariants: every returned process was alive at scan
 * time and either has {@code catalina} in its command line, is a Tomcat service wrapper, or is a
 * JVM whose command line this account cannot read ({@link TomcatProcess#opaque()}), because such a
 * JVM may be the Tomcat being watched; a scan that cannot run throws {@link TomcatScanException}
 * rather than returning an empty list; tests substitute a fake so controller state transitions can
 * be simulated without a real server.
 */
interface TomcatProcessFinder {

  /**
   * The Tomcat JVMs running now.
   *
   * @throws TomcatScanException when the process list cannot be obtained
   */
  List<TomcatProcess> find();

  /**
   * As {@link #find()}, plus service wrappers ({@code tomcatN.exe}) whose command line this account
   * cannot read, returned {@link TomcatProcess#opaque() opaque} with their listening ports. Only
   * reports use it (issue #147); the service steps keep {@link #find()}, where such wrappers are
   * left out (ADR-0014). The default adds nothing, since only Windows hides them.
   *
   * @throws TomcatScanException when the process list cannot be obtained
   */
  default List<TomcatProcess> findWithServiceWrappers() {
    return find();
  }

  /**
   * A Tomcat JVM with the install locations that could be read from its command line, and the TCP
   * ports it listens on where the scan can tell (empty when it cannot or there are none).
   */
  record TomcatProcess(
      long pid,
      String commandLine,
      Optional<Path> catalinaHome,
      Optional<Path> catalinaBase,
      Optional<Path> workingDir,
      Set<Integer> listeningPorts) {

    public TomcatProcess {
      listeningPorts = Set.copyOf(listeningPorts);
    }

    TomcatProcess(
        long pid,
        String commandLine,
        Optional<Path> catalinaHome,
        Optional<Path> catalinaBase,
        Optional<Path> workingDir) {
      this(pid, commandLine, catalinaHome, catalinaBase, workingDir, Set.of());
    }

    /** This process with {@code ports} as its listening ports. */
    TomcatProcess withListeningPorts(Set<Integer> ports) {
      return new TomcatProcess(pid, commandLine, catalinaHome, catalinaBase, workingDir, ports);
    }

    /**
     * True when the process runs the Tomcat under {@code dir}: its command line mentions the
     * directory, or its catalina.home/base lies beneath it. Comparison is case-insensitive because
     * Windows paths are.
     */
    boolean belongsTo(Path dir) {
      Path wanted = dir.toAbsolutePath().normalize();
      if (catalinaHome.map(h -> h.startsWith(wanted)).orElse(false)
          || catalinaBase.map(b -> b.startsWith(wanted)).orElse(false)) {
        return true;
      }
      if (commandLine.isBlank()) {
        return false;
      }
      String needle = wanted.toString().toLowerCase(Locale.ROOT);
      return commandLine.toLowerCase(Locale.ROOT).contains(needle);
    }

    /** A JVM nothing could be read from: it may or may not be the watched Tomcat. */
    boolean opaque() {
      return commandLine.isBlank() && catalinaHome.isEmpty() && catalinaBase.isEmpty();
    }
  }
}
