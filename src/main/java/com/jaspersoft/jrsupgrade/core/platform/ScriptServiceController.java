package com.jaspersoft.jrsupgrade.core.platform;

import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.LongPredicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link ServiceController} for installs controlled by a script: the bundled {@code ctlscript}
 * ({@code ctlscript stop tomcat}) or Tomcat's own {@code catalina} script ({@code catalina stop}).
 * Invariants: state is derived from running processes, not from the script, because neither script
 * reports status reliably; a Tomcat counts as running when a JVM with {@code catalina} in its
 * command line belongs to the watched directory, which is the install dir for {@code ctlscript}
 * (the script's parent) and the Tomcat dir for {@code catalina} (the parent of its {@code bin}), so
 * a Tomcat elsewhere on the machine is never mistaken for this one; the script is invoked with the
 * operation timeout and the remainder is spent polling. When {@code service.forceStopAfterSeconds}
 * is set and the watched Tomcat's JVM is still alive that long after the stop script returned, the
 * JVMs that belong to the watched directory, and only those, are ended forcibly once and the fact
 * is logged at WARN; a JVM whose command line cannot be read is never ended, and a cancelled stop
 * ends nothing (ADR-0016, issue #42).
 */
public final class ScriptServiceController extends PollingServiceController {

  private static final Logger LOG = LoggerFactory.getLogger(ScriptServiceController.class);

  private static final String SCRIPT_REMEDIATION =
      "check that the script exists, is executable by this account, and stops or starts Tomcat"
          + " when run by hand";

  /** Ends a process by pid; answers whether the operating system accepted the request. */
  @FunctionalInterface
  interface ProcessTerminator {

    boolean terminate(long pid);

    ProcessTerminator FORCIBLY =
        pid -> ProcessHandle.of(pid).map(ProcessHandle::destroyForcibly).orElse(false);
  }

  private final ServiceConfig.Kind kind;
  private final Path script;
  private final Path watchedDir;
  private final TomcatProcessFinder processes;
  private final Optional<Duration> forceStopAfter;
  private final ProcessTerminator terminator;
  private final LongPredicate alive;

  public ScriptServiceController(ProcessRunner runner, ServiceConfig.Kind kind, Path script) {
    this(runner, kind, script, TomcatProcesses.INSTANCE, DEFAULT_POLL_INTERVAL);
  }

  ScriptServiceController(
      ProcessRunner runner,
      ServiceConfig.Kind kind,
      Path script,
      TomcatProcessFinder processes,
      Duration pollInterval) {
    this(
        runner,
        kind,
        script,
        processes,
        pollInterval,
        Optional.empty(),
        ProcessTerminator.FORCIBLY);
  }

  ScriptServiceController(
      ProcessRunner runner,
      ServiceConfig.Kind kind,
      Path script,
      TomcatProcessFinder processes,
      Duration pollInterval,
      Optional<Duration> forceStopAfter,
      ProcessTerminator terminator) {
    this(
        runner,
        kind,
        script,
        processes,
        pollInterval,
        forceStopAfter,
        terminator,
        StalePidFile.LIVE_PROCESSES);
  }

  ScriptServiceController(
      ProcessRunner runner,
      ServiceConfig.Kind kind,
      Path script,
      TomcatProcessFinder processes,
      Duration pollInterval,
      Optional<Duration> forceStopAfter,
      ProcessTerminator terminator,
      LongPredicate alive) {
    super(runner, pollInterval);
    this.alive = requireNonNull(alive, "alive");
    this.kind = requireNonNull(kind, "kind");
    if (kind != ServiceConfig.Kind.CTLSCRIPT && kind != ServiceConfig.Kind.CATALINA) {
      throw new IllegalArgumentException("not a script kind: " + kind);
    }
    this.script = requireNonNull(script, "script").toAbsolutePath().normalize();
    this.watchedDir = watchedDirOf(kind, this.script);
    this.processes = requireNonNull(processes, "processes");
    this.forceStopAfter = requireNonNull(forceStopAfter, "forceStopAfter");
    this.terminator = requireNonNull(terminator, "terminator");
  }

  /** {@code <install>/ctlscript.sh} or {@code <tomcat>/bin/catalina.sh}. */
  static Path watchedDirOf(ServiceConfig.Kind kind, Path script) {
    Path parent = Optional.ofNullable(script.getParent()).orElse(script);
    return switch (kind) {
      case CTLSCRIPT -> parent;
      case CATALINA -> Optional.ofNullable(parent.getParent()).orElse(parent);
      case WINDOWS_SERVICE, SYSTEMD, MANUAL ->
          throw new IllegalArgumentException("not a script kind: " + kind);
    };
  }

  /** The directory whose Tomcat this controller watches. */
  public Path watchedDir() {
    return watchedDir;
  }

  /**
   * Process-based; the ports of the watched Tomcat's {@code server.xml} decide whether a JVM that
   * cannot be inspected might be it (ADR-0014).
   */
  @Override
  public State state() {
    return TomcatState.of(processes, Optional.of(watchedDir), ServerXml.portsUnder(watchedDir));
  }

  @Override
  public State stop(Duration timeout) {
    return stop(timeout, () -> false);
  }

  @Override
  public State stop(Duration timeout, BooleanSupplier cancelled) {
    long start = System.nanoTime();
    if (state() == State.STOPPED) {
      return State.STOPPED;
    }
    // Fails fast when the script could not run or refused (review 1.12 gave sc.exe and systemctl
    // this; the script kinds waited out the whole timeout instead; assessment item P3).
    control(command("stop"), timeout, State.STOPPED, SCRIPT_REMEDIATION);
    if (forceStopAfter.isEmpty()) {
      return await(State.STOPPED, remaining(start, timeout), cancelled);
    }
    Duration left = remaining(start, timeout);
    Duration grace = forceStopAfter.get().compareTo(left) < 0 ? forceStopAfter.get() : left;
    State afterGrace = await(State.STOPPED, grace, cancelled);
    if (afterGrace == State.STOPPED || cancelled.getAsBoolean()) {
      return afterGrace;
    }
    endWatchedTomcat(grace);
    return await(State.STOPPED, remaining(start, timeout), cancelled);
  }

  /**
   * JasperReports Server leaves non-daemon threads behind, so the JVM can outlive a stop script
   * that did its job (issue #42). Ends every readable JVM of the watched directory, once.
   */
  private void endWatchedTomcat(Duration grace) {
    List<TomcatProcessFinder.TomcatProcess> found;
    try {
      found = processes.find();
    } catch (TomcatScanException e) {
      LOG.warn(
          "{} still running after the stop script, but the process scan failed; nothing was"
              + " ended: {}",
          describe(),
          e.getMessage());
      return;
    }
    for (TomcatProcessFinder.TomcatProcess p : found) {
      if (p.opaque() || !p.belongsTo(watchedDir)) {
        continue;
      }
      boolean accepted = terminator.terminate(p.pid());
      LOG.warn(
          "{} still running {}s after the stop script; {} process {}"
              + " (service.forceStopAfterSeconds)",
          describe(),
          grace.toSeconds(),
          accepted ? "ending" : "could not end",
          p.pid());
    }
  }

  @Override
  public State start(Duration timeout) {
    return start(timeout, () -> false);
  }

  @Override
  public State start(Duration timeout, BooleanSupplier cancelled) {
    long start = System.nanoTime();
    if (state() == State.RUNNING) {
      return State.RUNNING;
    }
    removeStalePidFile();
    control(command("start"), timeout, State.RUNNING, SCRIPT_REMEDIATION);
    return await(State.RUNNING, remaining(start, timeout), cancelled);
  }

  /**
   * Where a bundled Tomcat's pid file can sit: the watched Tomcat, or the one inside an install.
   */
  List<Path> tomcatDirs() {
    return switch (kind) {
      case CATALINA -> List.of(watchedDir);
      case CTLSCRIPT -> List.of(watchedDir.resolve("apache-tomcat"), watchedDir.resolve("tomcat"));
      case WINDOWS_SERVICE, SYSTEMD, MANUAL -> throw new IllegalStateException(kind.name());
    };
  }

  /**
   * A {@code catalina.pid} left by a JVM that died without its stop script makes {@code catalina.sh
   * start} refuse (installation guide p.237, issue #113); one naming no live process is removed
   * before the start script runs. One naming a live process is left alone: that JVM is the reason
   * the start would fail, and ending it is the stop path's business.
   */
  private void removeStalePidFile() {
    try {
      StalePidFile.removeIfStale(tomcatDirs(), alive)
          .ifPresent(
              n ->
                  LOG.warn(
                      "removed stale {} naming {}; catalina.sh would have refused to start over it"
                          + " (installation guide p.237)",
                      n.file(),
                      n.pid()
                          .map(p -> "process " + p + ", which is not running")
                          .orElse("no process")));
    } catch (IOException e) {
      LOG.warn("cannot remove a stale pid file under {}: {}", watchedDir, e.getMessage());
    }
  }

  List<String> command(String operation) {
    return switch (kind) {
      case CTLSCRIPT -> List.of(script.toString(), operation, "tomcat");
      case CATALINA -> List.of(script.toString(), operation);
      case WINDOWS_SERVICE, SYSTEMD, MANUAL -> throw new IllegalStateException(kind.name());
    };
  }

  @Override
  public String describe() {
    return switch (kind) {
      case CTLSCRIPT -> "ctlscript " + script;
      case CATALINA -> "catalina script " + script;
      case WINDOWS_SERVICE, SYSTEMD, MANUAL -> throw new IllegalStateException(kind.name());
    };
  }
}
