package com.jaspersoft.jrsupgrade.core.platform;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

/**
 * The {@code service:} block of config.yaml (spec §5.1). Invariant: {@code forceStopAfter} is
 * present only for the {@code catalina} and {@code ctlscript} kinds and is shorter than {@code
 * stopTimeout}; {@code Config.toServiceConfig()} refuses any other combination (ADR-0016).
 */
public record ServiceConfig(
    Kind kind,
    Optional<String> name,
    Optional<Path> scriptPath,
    Duration stopTimeout,
    Optional<Duration> forceStopAfter) {

  /** A service block without {@code forceStopAfterSeconds}. */
  public ServiceConfig(
      Kind kind, Optional<String> name, Optional<Path> scriptPath, Duration stopTimeout) {
    this(kind, name, scriptPath, stopTimeout, Optional.empty());
  }

  public enum Kind {
    WINDOWS_SERVICE,
    SYSTEMD,
    CTLSCRIPT,
    CATALINA,
    MANUAL
  }
}
