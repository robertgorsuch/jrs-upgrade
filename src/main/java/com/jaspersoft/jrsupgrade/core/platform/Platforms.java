package com.jaspersoft.jrsupgrade.core.platform;

import static java.util.Objects.requireNonNull;

import java.util.Locale;
import java.util.Optional;

/**
 * Factory for the {@link Platform} implementation matching the running operating system. Invariant:
 * only the pair ADR-0002 supports is recognised, Windows or Linux on x86-64; every other host is
 * refused by {@link #detect()} with an {@link UnsupportedPlatformException} naming the observed
 * {@code os.name} and {@code os.arch}, because nothing below this point is written for macOS, BSD
 * or ARM64 (review 3.1).
 */
public final class Platforms {

  private Platforms() {}

  /** Platform for this machine with a non-interactive operator prompt. */
  public static Platform detect() {
    return detect(OperatorPrompt.nonInteractive());
  }

  /** Platform for this machine, routing manual-service instructions to {@code prompt}. */
  public static Platform detect(OperatorPrompt prompt) {
    return detect(System.getProperty("os.name", ""), System.getProperty("os.arch", ""), prompt);
  }

  /**
   * Platform for the named host, for tests that need a foreign {@code os.name} or {@code os.arch}.
   *
   * @throws UnsupportedPlatformException when the host is outside ADR-0002
   */
  public static Platform detect(String osName, String osArch, OperatorPrompt prompt) {
    Optional<String> refusal = unsupportedReason(osName, osArch);
    if (refusal.isPresent()) {
      throw new UnsupportedPlatformException(refusal.get());
    }
    Platform.OsFamily os = osFamily(osName).orElseThrow();
    Platform.Arch arch = arch(osArch);
    ProcessRunner runner = new DefaultProcessRunner();
    return switch (os) {
      case WINDOWS -> {
        TomcatProcessFinder tomcats = new WindowsTomcatProcesses(runner);
        yield new WindowsPlatform(arch, runner, new WindowsFileOps(tomcats), prompt, tomcats);
      }
      case LINUX -> new LinuxPlatform(arch, runner, new LinuxFileOps(), prompt);
    };
  }

  /**
   * Builds a platform of the given family with substituted collaborators, for tests that need a
   * fake {@link ProcessRunner} or {@link FileOps} regardless of the host OS.
   */
  public static Platform forTesting(
      Platform.OsFamily os,
      Platform.Arch arch,
      ProcessRunner runner,
      FileOps files,
      OperatorPrompt prompt) {
    requireNonNull(os, "os");
    return switch (os) {
      case WINDOWS -> new WindowsPlatform(arch, runner, files, prompt);
      case LINUX -> new LinuxPlatform(arch, runner, files, prompt);
    };
  }

  /**
   * Why this host is outside ADR-0002, or empty when it is Windows or Linux on x86-64. The text is
   * the one refusal message the CLI, {@code selfcheck} and {@code doctor} all print.
   */
  public static Optional<String> unsupportedReason(String osName, String osArch) {
    Optional<Platform.OsFamily> os = osFamily(osName);
    boolean archOk = arch(osArch) == Platform.Arch.X86_64;
    if (os.isPresent() && archOk) {
      return Optional.empty();
    }
    String what =
        os.isEmpty() && !archOk
            ? "operating system and architecture"
            : os.isEmpty() ? "operating system" : "architecture";
    return Optional.of(
        "unsupported "
            + what
            + ": "
            + describe(osName)
            + " "
            + describe(osArch)
            + "; jrs-upgrade runs on Windows or Linux on x86-64 only (ADR-0002)");
  }

  /**
   * Maps an {@code os.name} value to a family, or empty when it is neither Windows nor Linux. macOS
   * and BSD are empty rather than Linux: the Linux platform reads {@code /proc} and drives {@code
   * systemctl} (review 3.1).
   */
  public static Optional<Platform.OsFamily> osFamily(String osName) {
    String n = osName.toLowerCase(Locale.ROOT);
    if (n.contains("win")) {
      return Optional.of(Platform.OsFamily.WINDOWS);
    }
    return n.contains("linux") ? Optional.of(Platform.OsFamily.LINUX) : Optional.empty();
  }

  /** Maps an {@code os.arch} value ({@code amd64}, {@code x86_64}) to an architecture. */
  public static Platform.Arch arch(String osArch) {
    String a = osArch.toLowerCase(Locale.ROOT);
    return a.equals("amd64") || a.equals("x86_64") || a.equals("x64")
        ? Platform.Arch.X86_64
        : Platform.Arch.OTHER;
  }

  private static String describe(String value) {
    return value == null || value.isBlank() ? "(unknown)" : value;
  }
}
