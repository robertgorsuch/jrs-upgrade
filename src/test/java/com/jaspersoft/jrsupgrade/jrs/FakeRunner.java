package com.jaspersoft.jrsupgrade.jrs;

import com.jaspersoft.jrsupgrade.core.platform.ProcessRunner;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Scripted {@link ProcessRunner}: an exact command maps to an exit code and output lines; an
 * unscripted command exits 1 silently. Every invocation is recorded.
 */
public final class FakeRunner implements ProcessRunner {

  public record Scripted(int exitCode, OutputLine.Stream stream, List<String> lines) {}

  private final Map<List<String>, Scripted> scripted = new HashMap<>();
  private final List<List<String>> invocations = new ArrayList<>();

  public FakeRunner on(
      List<String> command, int exitCode, OutputLine.Stream stream, String... lines) {
    scripted.put(List.copyOf(command), new Scripted(exitCode, stream, List.of(lines)));
    return this;
  }

  public List<List<String>> invocations() {
    return List.copyOf(invocations);
  }

  @Override
  public Result run(Request request, Consumer<OutputLine> onLine) {
    invocations.add(List.copyOf(request.command()));
    Scripted s = scripted.get(request.command());
    if (s == null) {
      return new Result(1, false, Duration.ZERO);
    }
    for (String line : s.lines()) {
      onLine.accept(new OutputLine(s.stream(), line));
    }
    return new Result(s.exitCode(), false, Duration.ofMillis(1));
  }
}
