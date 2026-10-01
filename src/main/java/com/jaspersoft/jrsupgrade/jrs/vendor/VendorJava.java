package com.jaspersoft.jrsupgrade.jrs.vendor;

import com.jaspersoft.jrsupgrade.core.platform.ProcessRunner;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects the major version of the JDK at {@code vendor.javaHome} (spec §5.1, §12.1) by running
 * {@code <javaHome>/bin/java -version} through the {@link ProcessRunner} (no shell). Invariants:
 * {@code "11.0.24"} and {@code "17.0.9"} yield 11 and 17, the legacy {@code "1.8.0_392"} spelling
 * yields 8; both output streams are scanned; an unrunnable or unparseable command yields empty,
 * never a guess.
 */
public final class VendorJava {

  public static final Duration TIMEOUT = Duration.ofSeconds(30);

  private static final Pattern VERSION =
      Pattern.compile("version\\s+\"(\\d+)(?:\\.(\\d+))?[^\"]*\"");

  private VendorJava() {}

  public static Optional<Integer> detect(Path javaHome, ProcessRunner runner) {
    Objects.requireNonNull(javaHome, "javaHome");
    Objects.requireNonNull(runner, "runner");
    List<String> command = command(javaHome);
    List<String> lines = new ArrayList<>();
    runner.run(
        new ProcessRunner.Request(command, Optional.of(javaHome), Map.of(), TIMEOUT),
        line -> lines.add(line.text()));
    return lines.stream().map(VendorJava::parseMajor).flatMap(Optional::stream).findFirst();
  }

  /** The argument list {@code detect} runs: {@code <javaHome>/bin/java -version}. */
  public static List<String> command(Path javaHome) {
    return List.of(javaHome.resolve("bin").resolve("java").toString(), "-version");
  }

  /** Major version from one line of {@code java -version} output, if the line carries one. */
  public static Optional<Integer> parseMajor(String line) {
    Matcher m = VERSION.matcher(line);
    if (!m.find()) {
      return Optional.empty();
    }
    int major = Integer.parseInt(m.group(1));
    if (major == 1 && m.group(2) != null) {
      return Optional.of(Integer.parseInt(m.group(2)));
    }
    return Optional.of(major);
  }
}
