package com.jaspersoft.jrsupgrade.ops.doctor;

import com.jaspersoft.jrsupgrade.core.platform.ProcessRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Feature version of a JDK found at a {@code JAVA_HOME}, obtained by running {@code bin/java
 * -version} through the platform's {@link ProcessRunner} (spec §5.1: {@code vendor.javaHome} is
 * verified against the compat matrix). Invariants: the version banner is parsed from either stream;
 * {@code 1.8.0_x} maps to 8 and {@code 17.0.2} to 17; a JDK that cannot run yields an empty result
 * with the reason.
 */
final class JavaVersion {

  private static final Duration TIMEOUT = Duration.ofSeconds(30);
  private static final Pattern BANNER =
      Pattern.compile("version \"([0-9]+)(?:\\.([0-9]+))?[^\"]*\"");

  record Probe(Optional<Integer> feature, String detail) {}

  private JavaVersion() {}

  static Probe probe(ProcessRunner runner, Path javaHome) {
    Path bin = javaHome.resolve("bin");
    Path java =
        Files.isRegularFile(bin.resolve("java.exe"))
            ? bin.resolve("java.exe")
            : bin.resolve("java");
    if (!Files.isRegularFile(java)) {
      return new Probe(Optional.empty(), java + " does not exist");
    }
    List<String> lines = new ArrayList<>();
    ProcessRunner.Result result;
    try {
      result =
          runner.run(
              new ProcessRunner.Request(
                  List.of(java.toString(), "-version"), Optional.empty(), Map.of(), TIMEOUT),
              line -> lines.add(line.text()));
    } catch (RuntimeException e) {
      return new Probe(Optional.empty(), "cannot run " + java + ": " + e.getMessage());
    }
    if (!result.ok()) {
      return new Probe(
          Optional.empty(), java + " -version exited with " + result.exitCode() + " " + lines);
    }
    for (String line : lines) {
      Optional<Integer> feature = parseFeature(line);
      if (feature.isPresent()) {
        return new Probe(feature, line.strip());
      }
    }
    return new Probe(Optional.empty(), "no version banner in " + lines);
  }

  /** {@code 1.8.0_292} yields 8; {@code 11.0.2} yields 11; {@code 17} yields 17. */
  static Optional<Integer> parseFeature(String bannerLine) {
    Matcher m = BANNER.matcher(bannerLine);
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
