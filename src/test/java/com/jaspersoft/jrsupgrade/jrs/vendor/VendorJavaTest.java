package com.jaspersoft.jrsupgrade.jrs.vendor;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsupgrade.core.platform.ProcessRunner;
import com.jaspersoft.jrsupgrade.jrs.FakeRunner;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class VendorJavaTest {

  private static final Path JAVA_HOME = Path.of("opt", "jrs", "java");

  @ParameterizedTest
  @CsvSource({
    "'openjdk version \"11.0.24\" 2024-07-16', 11",
    "'java version \"17.0.9\" 2023-10-17 LTS', 17",
    "'openjdk version \"1.8.0_392\"', 8",
    "'openjdk version \"21\" 2023-09-19', 21"
  })
  void should_parse_major_when_output_uses_each_known_format(String line, int expected) {
    FakeRunner runner =
        new FakeRunner()
            .on(
                VendorJava.command(JAVA_HOME),
                0,
                ProcessRunner.OutputLine.Stream.STDERR,
                line,
                "OpenJDK Runtime Environment (build x)",
                "OpenJDK 64-Bit Server VM (build y, mixed mode)");

    Optional<Integer> major = VendorJava.detect(JAVA_HOME, runner);

    assertThat(major).contains(expected);
    assertThat(runner.invocations())
        .containsExactly(List.of(JAVA_HOME.resolve("bin").resolve("java").toString(), "-version"));
  }

  @Test
  void should_return_empty_when_java_cannot_run() {
    assertThat(VendorJava.detect(JAVA_HOME, new FakeRunner())).isEmpty();
  }

  @Test
  void should_return_empty_when_output_has_no_version_line() {
    FakeRunner runner =
        new FakeRunner()
            .on(
                VendorJava.command(JAVA_HOME),
                0,
                ProcessRunner.OutputLine.Stream.STDOUT,
                "Usage: java [options]");

    assertThat(VendorJava.detect(JAVA_HOME, runner)).isEmpty();
    assertThat(VendorJava.parseMajor("nothing here")).isEmpty();
  }
}
