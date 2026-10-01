package com.jaspersoft.jrsupgrade.core.platform;

import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base for every {@link ServiceController}: issues the platform command, then polls {@link
 * #state()} until the wanted state appears or the deadline passes. Invariants: the returned state
 * is always the last observed one, never an assumption; polling interval defaults to one second and
 * is shortened for the final wait so the timeout is honoured to within one interval; a state query
 * that cannot run yields {@link ServiceController.State#UNKNOWN}, while a control command that
 * cannot run or is refused raises {@link ServiceControlException} at once rather than being waited
 * out; a wait given a cancellation signal returns the last observed state as soon as it fires.
 */
abstract class PollingServiceController implements ServiceController {

  private static final Logger LOG = LoggerFactory.getLogger(PollingServiceController.class);
  static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(1);
  static final Duration QUERY_TIMEOUT = Duration.ofSeconds(30);

  protected final ProcessRunner runner;
  private final Duration pollInterval;

  PollingServiceController(ProcessRunner runner, Duration pollInterval) {
    this.runner = requireNonNull(runner, "runner");
    this.pollInterval = requireNonNull(pollInterval, "pollInterval");
  }

  /** Polls until {@code wanted} is observed or {@code timeout} elapses; returns the last state. */
  protected final State await(State wanted, Duration timeout) {
    return await(wanted, timeout, () -> false);
  }

  /**
   * As above, also giving up as soon as {@code cancelled} answers true (checked before every nap,
   * and the nap itself is capped at one poll interval), so a cancelled run does not sit out a
   * three-minute service timeout.
   */
  protected final State await(State wanted, Duration timeout, BooleanSupplier cancelled) {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (true) {
      State current = state();
      long remaining = deadline - System.nanoTime();
      if (current == wanted || remaining <= 0 || cancelled.getAsBoolean()) {
        return current;
      }
      long nap = Math.min(pollInterval.toNanos(), remaining);
      try {
        Thread.sleep(Duration.ofNanos(nap));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return current;
      }
    }
  }

  /**
   * Issues the control command and refuses to wait when the platform refused it: a command that
   * could not start, or exited non-zero while the service is neither in {@code wanted} nor on its
   * way there, raises {@link ServiceControlException} at once instead of letting the caller poll
   * out its timeout. A non-zero exit with the service already heading for {@code wanted} (a stop
   * issued during STOP_PENDING, say) is treated as redundant and waited out normally.
   */
  protected final void control(
      List<String> command, Duration timeout, State wanted, String remediation) {
    Optional<Invocation> run = invoke(command, timeout);
    if (run.isEmpty()) {
      throw new ServiceControlException(
          command,
          -1,
          String.join(" ", command) + " could not be started on this machine; " + remediation);
    }
    if (run.get().result().ok()) {
      return;
    }
    State now = state();
    State pending = wanted == State.STOPPED ? State.STOPPING : State.STARTING;
    if (now == wanted || now == pending) {
      return;
    }
    List<String> text = run.get().text();
    String tail = String.join(" | ", text.subList(Math.max(0, text.size() - 3), text.size()));
    throw new ServiceControlException(
        command,
        run.get().result().exitCode(),
        String.join(" ", command)
            + " exited "
            + run.get().result().exitCode()
            + (tail.isBlank() ? "" : ": " + tail)
            + "; "
            + remediation);
  }

  /**
   * Runs {@code command} with the given timeout, collecting every output line; empty when the
   * program could not be started at all.
   */
  protected final Optional<Invocation> invoke(List<String> command, Duration timeout) {
    List<ProcessRunner.OutputLine> lines = new ArrayList<>();
    Consumer<ProcessRunner.OutputLine> collect = lines::add;
    try {
      ProcessRunner.Result result =
          runner.run(
              new ProcessRunner.Request(command, Optional.empty(), java.util.Map.of(), timeout),
              collect);
      return Optional.of(new Invocation(result, List.copyOf(lines)));
    } catch (RuntimeException e) {
      LOG.warn("cannot run {}: {}", command.get(0), e.toString());
      return Optional.empty();
    }
  }

  static Duration remaining(long startNanos, Duration budget) {
    Duration used = Duration.ofNanos(System.nanoTime() - startNanos);
    Duration left = budget.minus(used);
    return left.isNegative() ? Duration.ZERO : left;
  }

  /** Result and captured output of one command. */
  record Invocation(ProcessRunner.Result result, List<ProcessRunner.OutputLine> lines) {
    List<String> text() {
      return lines.stream().map(ProcessRunner.OutputLine::text).toList();
    }
  }
}
