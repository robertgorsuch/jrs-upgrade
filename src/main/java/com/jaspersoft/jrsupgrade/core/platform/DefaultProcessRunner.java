package com.jaspersoft.jrsupgrade.core.platform;

import static java.util.Objects.requireNonNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link ProcessRunner} backed by {@link ProcessBuilder}. Invariants: the command is passed as an
 * argument list and no shell is ever involved; stdout and stderr are each drained by a dedicated
 * daemon thread and delivered line by line, in per-stream order, under a single lock so the
 * consumer never sees interleaved partial lines; when the request timeout elapses the whole process
 * tree (descendants first, then the process) is destroyed forcibly and the result reports {@code
 * timedOut=true}. A zero or negative timeout means "wait forever". A program that cannot be started
 * at all (missing binary, unreadable working directory) surfaces as {@link UncheckedIOException}
 * because there is no exit code to report.
 */
public final class DefaultProcessRunner implements ProcessRunner {

  private static final Logger LOG = LoggerFactory.getLogger(DefaultProcessRunner.class);
  private static final Duration PUMP_DRAIN_GRACE = Duration.ofSeconds(5);

  private final java.util.function.Supplier<Charset> outputCharset;

  /**
   * Decodes process output with the encoding the host's console programs write: on Windows the OEM
   * console code page, elsewhere {@code native.encoding} (review 3.5). The Windows answer is
   * resolved on the first run, not at construction, so nothing is probed at start-up.
   */
  public DefaultProcessRunner() {
    this.outputCharset = WindowsCodePage::consoleCharset;
  }

  public DefaultProcessRunner(Charset outputCharset) {
    requireNonNull(outputCharset, "outputCharset");
    this.outputCharset = () -> outputCharset;
  }

  /**
   * The charset console programs on this machine write with ({@code native.encoding}), falling back
   * to the JVM default when the property is absent or unknown.
   */
  public static Charset nativeCharset() {
    String name = System.getProperty("native.encoding");
    return name == null
        ? Charset.defaultCharset()
        : Charset.forName(name, Charset.defaultCharset());
  }

  @Override
  public Optional<String> launch(List<String> command) {
    requireNonNull(command, "command");
    if (command.isEmpty()) {
      throw new IllegalArgumentException("command must not be empty");
    }
    ProcessBuilder builder =
        new ProcessBuilder(command)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD);
    try {
      Process process = builder.start();
      // No stdin either: the child reads end-of-input instead of waiting on a pipe nobody feeds.
      process.getOutputStream().close();
      return Optional.empty();
    } catch (IOException e) {
      return Optional.of(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
    }
  }

  /**
   * The child's environment: {@code inherited} without the names in {@code request.unset()}
   * (compared without regard to case), then the request's own variables on top (issue #47).
   */
  static Map<String, String> childEnvironment(
      Map<String, String> inherited, ProcessRunner.Request request) {
    Map<String, String> child = new LinkedHashMap<>();
    for (Map.Entry<String, String> e : inherited.entrySet()) {
      boolean dropped = request.unset().stream().anyMatch(n -> n.equalsIgnoreCase(e.getKey()));
      if (!dropped) {
        child.put(e.getKey(), e.getValue());
      }
    }
    child.putAll(request.environment());
    return child;
  }

  @Override
  public Result run(Request request, Consumer<OutputLine> onLine) {
    requireNonNull(request, "request");
    requireNonNull(onLine, "onLine");
    if (request.command().isEmpty()) {
      throw new IllegalArgumentException("command must not be empty");
    }
    ProcessBuilder builder = new ProcessBuilder(request.command());
    // ProcessBuilder only accepts java.io.File for the directory; this is the single sanctioned
    // use.
    request.workingDir().ifPresent(dir -> builder.directory(dir.toFile()));
    Map<String, String> env = builder.environment();
    Map<String, String> wanted = childEnvironment(Map.copyOf(env), request);
    env.clear();
    env.putAll(wanted);

    long startNanos = System.nanoTime();
    Process process;
    try {
      process = builder.start();
      process.getOutputStream().close();
    } catch (IOException e) {
      throw new UncheckedIOException("cannot start " + request.command().get(0), e);
    }

    Object deliveryLock = new Object();
    Thread stdout = pump(process.getInputStream(), OutputLine.Stream.STDOUT, onLine, deliveryLock);
    Thread stderr = pump(process.getErrorStream(), OutputLine.Stream.STDERR, onLine, deliveryLock);

    boolean timedOut = false;
    try {
      if (!waitFor(process, request.timeout())) {
        timedOut = true;
        LOG.warn(
            "{} did not finish within {}; destroying process tree",
            request.command().get(0),
            request.timeout());
        destroyTree(process);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      timedOut = true;
      destroyTree(process);
    }
    join(stdout);
    join(stderr);

    int exitCode = process.isAlive() ? -1 : process.exitValue();
    Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
    return new Result(exitCode, timedOut, elapsed);
  }

  private static boolean waitFor(Process process, Duration timeout) throws InterruptedException {
    if (timeout.isZero() || timeout.isNegative()) {
      process.waitFor();
      return true;
    }
    return process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
  }

  private static void destroyTree(Process process) {
    process.descendants().forEach(ProcessHandle::destroyForcibly);
    process.destroyForcibly();
    try {
      process.waitFor(PUMP_DRAIN_GRACE.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static void join(Thread pump) {
    try {
      pump.join(PUMP_DRAIN_GRACE.toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private Thread pump(
      InputStream in, OutputLine.Stream stream, Consumer<OutputLine> onLine, Object lock) {
    Thread thread =
        new Thread(
            () -> drain(in, stream, onLine, lock),
            "jrs-upgrade-" + stream.name().toLowerCase(Locale.ROOT) + "-pump");
    thread.setDaemon(true);
    thread.start();
    return thread;
  }

  private void drain(
      InputStream in, OutputLine.Stream stream, Consumer<OutputLine> onLine, Object lock) {
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(in, outputCharset.get()))) {
      String line;
      while ((line = reader.readLine()) != null) {
        OutputLine output = new OutputLine(stream, line);
        synchronized (lock) {
          try {
            onLine.accept(output);
          } catch (RuntimeException e) {
            // keep draining so the child never blocks on a full pipe
            LOG.warn("output consumer failed on {} line", stream, e);
          }
        }
      }
    } catch (IOException e) {
      LOG.debug("{} pipe closed early", stream, e);
    }
  }
}
