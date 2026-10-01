package com.jaspersoft.jrsupgrade.core.platform;

import static java.util.Objects.requireNonNull;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * {@link ServiceController} for {@code service.kind: manual}: the operator stops and starts Tomcat
 * by hand. Invariants: in a non-interactive session {@link #stop} and {@link #start} return {@code
 * UNKNOWN} at once without prompting, so the calling step fails with a clear "needs an interactive
 * session" message instead of hanging; in an interactive session the operator is instructed once
 * and running processes are polled until the transition is observed or the timeout elapses; state
 * is process-based exactly as for {@link ScriptServiceController}.
 */
public final class ManualServiceController extends PollingServiceController {

  private final OperatorPrompt prompt;
  private final Optional<Path> installDir;
  private final TomcatProcessFinder processes;

  public ManualServiceController(
      ProcessRunner runner, OperatorPrompt prompt, Optional<Path> installDir) {
    this(runner, prompt, installDir, TomcatProcesses.INSTANCE, DEFAULT_POLL_INTERVAL);
  }

  ManualServiceController(
      ProcessRunner runner,
      OperatorPrompt prompt,
      Optional<Path> installDir,
      TomcatProcessFinder processes,
      Duration pollInterval) {
    super(runner, pollInterval);
    this.prompt = requireNonNull(prompt, "prompt");
    this.installDir = requireNonNull(installDir, "installDir").map(Path::toAbsolutePath);
    this.processes = requireNonNull(processes, "processes");
  }

  @Override
  public State state() {
    return TomcatState.of(
        processes, installDir, installDir.map(ServerXml::portsUnder).orElse(Set.of()));
  }

  @Override
  public State stop(Duration timeout) {
    return stop(timeout, () -> false);
  }

  @Override
  public State stop(Duration timeout, BooleanSupplier cancelled) {
    if (state() == State.STOPPED) {
      return State.STOPPED;
    }
    if (!prompt.interactive()) {
      return State.UNKNOWN;
    }
    prompt.instruct(
        "Stop the JasperReports Server Tomcat"
            + installDir.map(d -> " under " + d).orElse("")
            + " now; jrs-upgrade will continue once it is no longer running (waiting up to "
            + timeout.toSeconds()
            + " s).");
    return await(State.STOPPED, timeout, cancelled);
  }

  @Override
  public State start(Duration timeout) {
    return start(timeout, () -> false);
  }

  @Override
  public State start(Duration timeout, BooleanSupplier cancelled) {
    if (state() == State.RUNNING) {
      return State.RUNNING;
    }
    if (!prompt.interactive()) {
      return State.UNKNOWN;
    }
    prompt.instruct(
        "Start the JasperReports Server Tomcat"
            + installDir.map(d -> " under " + d).orElse("")
            + " now; jrs-upgrade will continue once it is running (waiting up to "
            + timeout.toSeconds()
            + " s).");
    return await(State.RUNNING, timeout, cancelled);
  }

  @Override
  public String describe() {
    return "manual (operator stops and starts Tomcat"
        + installDir.map(d -> " under " + d).orElse("")
        + ")";
  }
}
