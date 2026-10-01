package com.jaspersoft.jrsupgrade.core.platform;

import static java.util.Objects.requireNonNull;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * {@link ServiceController} for a systemd unit driven by {@code systemctl}. Invariants: state is
 * the first line of {@code systemctl is-active <unit>} ({@code active}, {@code inactive}, {@code
 * failed}, {@code activating}, {@code deactivating}); {@code stop}/{@code start} block for at most
 * the given timeout and then poll for the remainder, so a unit with a long {@code TimeoutStopSec}
 * is still reported truthfully. A unit named {@code X.socket} is a socket-activated Tomcat (the
 * vendor's AWS images, AWS guide p.30, issue #113): its state is that of {@code X.service}, the
 * JVM, and stop and start act on both units, because a socket left listening starts the service
 * again on the next request, in the middle of a run.
 */
public final class SystemdServiceController extends PollingServiceController {

  private final String unit;
  private final String serviceUnit;
  private final List<String> controlled;
  private final TomcatProcessFinder processes;
  private final Optional<Path> installDir;

  public SystemdServiceController(ProcessRunner runner, String unit) {
    this(runner, unit, TomcatProcesses.INSTANCE, Optional.empty(), DEFAULT_POLL_INTERVAL);
  }

  SystemdServiceController(ProcessRunner runner, String unit, Duration pollInterval) {
    this(runner, unit, TomcatProcesses.INSTANCE, Optional.empty(), pollInterval);
  }

  SystemdServiceController(
      ProcessRunner runner,
      String unit,
      TomcatProcessFinder processes,
      Optional<Path> installDir,
      Duration pollInterval) {
    super(runner, pollInterval);
    this.unit = requireNonNull(unit, "unit");
    this.serviceUnit = serviceUnitOf(unit);
    this.controlled = unit.endsWith(SOCKET) ? List.of(unit, serviceUnit) : List.of(unit);
    this.processes = requireNonNull(processes, "processes");
    this.installDir = requireNonNull(installDir, "installDir");
  }

  @Override
  public State state() {
    Optional<Invocation> query =
        invoke(List.of("systemctl", "is-active", serviceUnit), QUERY_TIMEOUT);
    if (query.isEmpty()) {
      return State.UNKNOWN;
    }
    // is-active exits non-zero for every state but active; the text is what matters
    State parsed = parseState(query.get().text());
    if (parsed == State.STOPPED && TomcatState.of(processes, installDir) == State.RUNNING) {
      // A unit with KillMode=none (common in hand-written units whose ExecStop is shutdown.sh)
      // reports inactive while the JVM is still going down; until it is gone the files under
      // WEB-INF are not free to swap, so the state is "stopping" (assessment item H5).
      return State.STOPPING;
    }
    return parsed;
  }

  static State parseState(List<String> lines) {
    for (String line : lines) {
      String word = line.trim().toLowerCase(Locale.ROOT);
      if (word.isEmpty()) {
        continue;
      }
      return switch (word) {
        case "active", "reloading" -> State.RUNNING;
        case "inactive", "failed" -> State.STOPPED;
        case "activating" -> State.STARTING;
        case "deactivating" -> State.STOPPING;
        default -> State.UNKNOWN;
      };
    }
    return State.UNKNOWN;
  }

  private static final String REMEDIATION =
      "run jrs-upgrade as root, or as a user allowed to control the unit (polkit or sudo)";

  @Override
  public State stop(Duration timeout) {
    return stop(timeout, () -> false);
  }

  @Override
  public State stop(Duration timeout, BooleanSupplier cancelled) {
    long start = System.nanoTime();
    // a socket unit is stopped even when its service is already inactive: the socket alone
    // would start the service again on the next request
    if (state() == State.STOPPED && controlled.size() == 1) {
      return State.STOPPED;
    }
    control(command("stop"), timeout, State.STOPPED, REMEDIATION);
    return await(State.STOPPED, remaining(start, timeout), cancelled);
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
    control(command("start"), timeout, State.RUNNING, REMEDIATION);
    return await(State.RUNNING, remaining(start, timeout), cancelled);
  }

  @Override
  public String describe() {
    return unit.endsWith(SOCKET)
        ? "systemd socket unit " + unit + " with " + serviceUnit
        : "systemd unit " + unit;
  }

  static final String SOCKET = ".socket";

  /** {@code X.socket} is driven through {@code X.service}; any other unit stands for itself. */
  static String serviceUnitOf(String unit) {
    return unit.endsWith(SOCKET)
        ? unit.substring(0, unit.length() - SOCKET.length()) + ".service"
        : unit;
  }

  /** {@code systemctl <operation>} over every unit this controller drives, socket first. */
  List<String> command(String operation) {
    List<String> cmd = new ArrayList<>();
    cmd.add("systemctl");
    cmd.add(operation);
    cmd.addAll(controlled);
    return List.copyOf(cmd);
  }
}
