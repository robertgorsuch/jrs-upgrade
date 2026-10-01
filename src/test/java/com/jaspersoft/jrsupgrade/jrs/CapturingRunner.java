package com.jaspersoft.jrsupgrade.jrs;

import com.jaspersoft.jrsupgrade.core.platform.ProcessRunner;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * {@link ProcessRunner} that records every full {@link Request} and answers with a programmable
 * handler, so tests can assert the environment and working directory and simulate output, exit
 * codes, timeouts or side effects such as writing the output file a vendor tool would produce.
 */
public final class CapturingRunner implements ProcessRunner {

  private final List<Request> requests = new ArrayList<>();
  private BiFunction<Request, Consumer<OutputLine>, Result> handler =
      (request, onLine) -> new Result(0, false, Duration.ofMillis(1));

  public CapturingRunner answer(BiFunction<Request, Consumer<OutputLine>, Result> handler) {
    this.handler = handler;
    return this;
  }

  public CapturingRunner exit(int code, String... stdoutLines) {
    return answer(
        (request, onLine) -> {
          for (String l : stdoutLines) {
            onLine.accept(new OutputLine(OutputLine.Stream.STDOUT, l));
          }
          return new Result(code, false, Duration.ofMillis(1));
        });
  }

  public CapturingRunner timeOut() {
    return answer((request, onLine) -> new Result(-1, true, Duration.ofSeconds(1)));
  }

  public List<Request> requests() {
    return List.copyOf(requests);
  }

  public Request last() {
    return requests.get(requests.size() - 1);
  }

  @Override
  public Result run(Request request, Consumer<OutputLine> onLine) {
    requests.add(request);
    return handler.apply(request, onLine);
  }
}
