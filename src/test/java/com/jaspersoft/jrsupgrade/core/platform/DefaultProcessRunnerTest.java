package com.jaspersoft.jrsupgrade.core.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DefaultProcessRunnerTest {

  private final DefaultProcessRunner runner = new DefaultProcessRunner();

  @Test
  void should_stream_stdout_and_stderr_in_per_stream_order_when_child_interleaves_them() {
    List<ProcessRunner.OutputLine> lines = new CopyOnWriteArrayList<>();

    ProcessRunner.Result result = runner.run(helper("lines", Duration.ofSeconds(60)), lines::add);

    assertThat(result.ok()).as("result %s lines %s", result, lines).isTrue();
    assertThat(result.timedOut()).isFalse();
    assertThat(only(lines, ProcessRunner.OutputLine.Stream.STDOUT))
        .containsExactly("out-1", "out-2", "out-3");
    assertThat(only(lines, ProcessRunner.OutputLine.Stream.STDERR))
        .containsExactly("err-1", "err-2", "err-3");
    assertThat(lines.stream().map(ProcessRunner.OutputLine::text))
        .containsExactly("out-1", "err-1", "out-2", "err-2", "out-3", "err-3");
  }

  /** A launcher is started and left alone; only a failure to start is reported (item P1). */
  @Test
  void should_start_without_waiting_when_launching_and_report_only_a_failure_to_start() {
    String java = ProcessHandle.current().info().command().orElseThrow();

    assertThat(runner.launch(List.of(java, "-version"))).isEmpty();
    assertThat(runner.launch(List.of("jrs-upgrade-no-such-program-" + System.nanoTime())))
        .isPresent();
  }

  @Test
  void should_report_timeout_and_kill_child_when_it_outlives_the_budget() {
    List<ProcessRunner.OutputLine> lines = new CopyOnWriteArrayList<>();
    long start = System.nanoTime();

    ProcessRunner.Result result = runner.run(helper("sleep", Duration.ofSeconds(3)), lines::add);

    Duration wall = Duration.ofNanos(System.nanoTime() - start);
    assertThat(result.timedOut()).isTrue();
    assertThat(result.ok()).isFalse();
    assertThat(wall).isLessThan(Duration.ofSeconds(20));
    assertThat(lines.stream().map(ProcessRunner.OutputLine::text)).contains("sleeping");
  }

  @Test
  void should_pass_environment_and_working_dir_when_request_sets_them(@TempDir Path dir) {
    List<String> lines = new ArrayList<>();
    ProcessRunner.Request request =
        new ProcessRunner.Request(
            helperCommand("env"),
            Optional.of(dir),
            Map.of("JRS_UPGRADE_TEST", "hello"),
            Duration.ofSeconds(60));

    ProcessRunner.Result result = runner.run(request, l -> lines.add(l.text()));

    assertThat(result.ok()).isTrue();
    assertThat(lines).contains("JRS_UPGRADE_TEST=hello");
    assertThat(lines.stream().filter(l -> l.startsWith("cwd=")))
        .singleElement()
        .satisfies(
            l ->
                assertThat(Path.of(l.substring("cwd=".length())).toRealPath())
                    .isEqualTo(dir.toRealPath()));
  }

  /** Issue #47: names in {@code unset} are not inherited; an explicit value still wins. */
  @Test
  void should_drop_unset_variables_and_keep_explicit_ones_when_building_the_child_environment() {
    ProcessRunner.Request request =
        new ProcessRunner.Request(
            List.of("x"),
            Optional.empty(),
            Map.of("JAVA_HOME", "/jdk"),
            Duration.ofSeconds(1),
            Set.of("jrs_password", "JAVA_HOME"));

    Map<String, String> child =
        DefaultProcessRunner.childEnvironment(
            Map.of("JRS_PASSWORD", "secret", "PATH", "/bin", "JAVA_HOME", "/old"), request);

    assertThat(child).containsOnly(Map.entry("PATH", "/bin"), Map.entry("JAVA_HOME", "/jdk"));
  }

  @Test
  void should_return_exit_code_when_child_fails() {
    List<ProcessRunner.OutputLine> lines = new ArrayList<>();

    ProcessRunner.Result result = runner.run(helper("exit", Duration.ofSeconds(60)), lines::add);

    assertThat(result.exitCode()).isEqualTo(3);
    assertThat(result.ok()).isFalse();
    assertThat(only(lines, ProcessRunner.OutputLine.Stream.STDERR))
        .containsExactly("failing on purpose");
  }

  @Test
  void should_throw_unchecked_io_when_program_does_not_exist() {
    ProcessRunner.Request request =
        new ProcessRunner.Request(
            List.of("jrs-upgrade-no-such-program-" + System.nanoTime()),
            Optional.empty(),
            Map.of(),
            Duration.ofSeconds(5));

    assertThatThrownBy(() -> runner.run(request, l -> {}))
        .isInstanceOf(UncheckedIOException.class)
        .hasMessageContaining("cannot start");
  }

  private static List<String> only(
      List<ProcessRunner.OutputLine> lines, ProcessRunner.OutputLine.Stream stream) {
    return lines.stream()
        .filter(l -> l.stream() == stream)
        .map(ProcessRunner.OutputLine::text)
        .toList();
  }

  private static ProcessRunner.Request helper(String mode, Duration timeout) {
    return new ProcessRunner.Request(helperCommand(mode), Optional.empty(), Map.of(), timeout);
  }

  static List<String> helperCommand(String mode) {
    boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    Path java = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java");
    return List.of(
        java.toString(),
        "-cp",
        testClassesDir().toString(),
        ProcessRunnerHelper.class.getName(),
        mode);
  }

  private static Path testClassesDir() {
    try {
      return Path.of(
          ProcessRunnerHelper.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    } catch (URISyntaxException e) {
      throw new IllegalStateException(e);
    }
  }
}
