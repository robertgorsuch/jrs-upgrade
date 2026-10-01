package com.jaspersoft.jrsupgrade.core.platform;

import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link ServiceController} for a Windows service driven by {@code sc.exe}. Invariants: state comes
 * from the {@code STATE} line of {@code sc.exe query <name>} (a missing service or an unparsable
 * answer is {@code UNKNOWN}); {@code stop}/{@code start} issue the request once and then poll,
 * because {@code sc.exe} returns while the transition is still pending; a stop requested on a
 * service that is already stopped is a no-op.
 */
public final class WindowsServiceController extends PollingServiceController {

  private static final Pattern STATE_LINE =
      Pattern.compile("^\\s*STATE\\s*:\\s*\\d+\\s+([A-Z_]+)", Pattern.CASE_INSENSITIVE);

  private final String serviceName;

  public WindowsServiceController(ProcessRunner runner, String serviceName) {
    this(runner, serviceName, DEFAULT_POLL_INTERVAL);
  }

  WindowsServiceController(ProcessRunner runner, String serviceName, Duration pollInterval) {
    super(runner, pollInterval);
    this.serviceName = requireNonNull(serviceName, "serviceName");
  }

  @Override
  public State state() {
    Optional<Invocation> query = invoke(List.of("sc.exe", "query", serviceName), QUERY_TIMEOUT);
    if (query.isEmpty() || !query.get().result().ok()) {
      return State.UNKNOWN;
    }
    return parseState(query.get().text());
  }

  static State parseState(List<String> lines) {
    for (String line : lines) {
      Matcher m = STATE_LINE.matcher(line);
      if (m.find()) {
        return switch (m.group(1).toUpperCase(Locale.ROOT)) {
          case "RUNNING" -> State.RUNNING;
          case "STOPPED" -> State.STOPPED;
          case "START_PENDING", "CONTINUE_PENDING" -> State.STARTING;
          case "STOP_PENDING", "PAUSE_PENDING" -> State.STOPPING;
          default -> State.UNKNOWN;
        };
      }
    }
    return State.UNKNOWN;
  }

  private static final String REMEDIATION =
      "run jrs-upgrade from an elevated (administrator) prompt, or grant this account the right to"
          + " control the service";

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
    control(List.of("sc.exe", "stop", serviceName), QUERY_TIMEOUT, State.STOPPED, REMEDIATION);
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
    control(List.of("sc.exe", "start", serviceName), QUERY_TIMEOUT, State.RUNNING, REMEDIATION);
    return await(State.RUNNING, remaining(start, timeout), cancelled);
  }

  @Override
  public String describe() {
    return "Windows service " + serviceName;
  }
}
