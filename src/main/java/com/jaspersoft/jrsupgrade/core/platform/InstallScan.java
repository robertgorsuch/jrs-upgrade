package com.jaspersoft.jrsupgrade.core.platform;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Where {@code init} should look for an installation, and what the running-process scan could and
 * could not see (field test 3). Invariants: {@code candidates} are absolute, normalised, existing
 * directories, each once, running-Tomcat locations first; {@code running} holds the candidates
 * derived from a running Tomcat's command line; {@code processScanLimit}, when present, says in a
 * sentence why a running server may be missing from {@code running} (a JVM whose command line this
 * account cannot read, or a scan that failed), so the operator is told rather than misled.
 */
public record InstallScan(
    List<Path> candidates, Set<Path> running, Optional<String> processScanLimit) {

  public InstallScan {
    candidates = candidates.stream().map(InstallScan::normal).toList();
    running = running.stream().map(InstallScan::normal).collect(Collectors.toUnmodifiableSet());
    Objects.requireNonNull(processScanLimit, "processScanLimit");
  }

  /** True when {@code dir} was found through a running Tomcat. */
  public boolean isRunning(Path dir) {
    return running.contains(normal(dir));
  }

  private static Path normal(Path p) {
    return p.toAbsolutePath().normalize();
  }
}
