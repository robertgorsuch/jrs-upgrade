package com.jaspersoft.jrsupgrade.core.platform;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link Platform} for Windows. Invariants: the default home is {@code %ProgramData%\jrs-upgrade}
 * when that tree can be written, otherwise {@code ~\.jrs-upgrade}; install candidates are, in
 * order, the install dirs of running Tomcats, {@code InstallLocation} values from the Uninstall
 * registry keys (queried with {@code reg.exe}, failures tolerated) and the well-known Jaspersoft
 * directories under {@code C:\Jaspersoft} and {@code %ProgramFiles%}.
 */
public final class WindowsPlatform extends AbstractPlatform {

  private static final Logger LOG = LoggerFactory.getLogger(WindowsPlatform.class);
  private static final Duration REGISTRY_TIMEOUT = Duration.ofSeconds(20);
  private static final Pattern INSTALL_LOCATION =
      Pattern.compile("^\\s*InstallLocation\\s+REG_(?:EXPAND_)?SZ\\s+(.+?)\\s*$");
  private static final List<String> UNINSTALL_KEYS =
      List.of(
          "HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall",
          "HKLM\\SOFTWARE\\WOW6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall");

  public WindowsPlatform(Arch arch, ProcessRunner runner, FileOps files, OperatorPrompt prompt) {
    this(arch, runner, files, prompt, new WindowsTomcatProcesses(runner));
  }

  /** With the process finder shared by this platform and its file operations (issue #38). */
  WindowsPlatform(
      Arch arch,
      ProcessRunner runner,
      FileOps files,
      OperatorPrompt prompt,
      TomcatProcessFinder tomcats) {
    this(arch, runner, files, prompt, Optional.empty(), tomcats);
  }

  private WindowsPlatform(
      Arch arch,
      ProcessRunner runner,
      FileOps files,
      OperatorPrompt prompt,
      Optional<Path> installDir,
      TomcatProcessFinder tomcats) {
    super(arch, runner, files, prompt, installDir, tomcats);
  }

  @Override
  public Platform withInstallDir(Path installDir) {
    return new WindowsPlatform(
        arch(), processes(), files(), prompt(), Optional.of(installDir), tomcats());
  }

  @Override
  public OsFamily os() {
    return OsFamily.WINDOWS;
  }

  @Override
  public Path defaultHome() {
    Path programData =
        Path.of(Optional.ofNullable(System.getenv("ProgramData")).orElse("C:\\ProgramData"));
    return homeOrFallback(programData);
  }

  @Override
  List<Path> wellKnownInstallDirs() {
    List<Path> candidates = new ArrayList<>();
    candidates.addAll(installDirsFromRegistry());
    candidates.addAll(glob(Path.of("C:\\Jaspersoft"), "*"));
    Path programFiles =
        Path.of(Optional.ofNullable(System.getenv("ProgramFiles")).orElse("C:\\Program Files"));
    candidates.addAll(glob(programFiles, "jasperreports-server*"));
    candidates.addAll(glob(programFiles.resolve("Jaspersoft"), "*"));
    return candidates;
  }

  private List<Path> installDirsFromRegistry() {
    List<Path> found = new ArrayList<>();
    for (String key : UNINSTALL_KEYS) {
      List<String> command = List.of("reg.exe", "query", key, "/s", "/f", "JasperReports");
      try {
        processes()
            .run(
                new ProcessRunner.Request(command, Optional.empty(), Map.of(), REGISTRY_TIMEOUT),
                line -> parseInstallLocation(line.text()).ifPresent(found::add));
      } catch (RuntimeException e) {
        LOG.debug("registry query failed for {}", key, e);
      }
    }
    return found;
  }

  static Optional<Path> parseInstallLocation(String line) {
    Matcher m = INSTALL_LOCATION.matcher(line);
    if (!m.find()) {
      return Optional.empty();
    }
    try {
      return Optional.of(Path.of(m.group(1)));
    } catch (InvalidPathException e) {
      return Optional.empty();
    }
  }
}
