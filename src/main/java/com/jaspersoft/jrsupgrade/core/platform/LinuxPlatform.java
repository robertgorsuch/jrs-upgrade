package com.jaspersoft.jrsupgrade.core.platform;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@link Platform} for Linux. Invariants: the default home is {@code /var/lib/jrs-upgrade} when it
 * exists or can be created (root or a dedicated service account) and {@code ~/.jrs-upgrade} only
 * when no system home exists; a system home this user cannot write to is refused by the resolver,
 * never silently replaced by a per-user one; install candidates are the install dirs of running
 * Tomcats (from {@code /proc/<pid>/cwd} and {@code catalina.home}) followed by the well-known
 * locations under {@code /opt}, {@code /usr/local} and every home directory.
 */
public final class LinuxPlatform extends AbstractPlatform {

  public LinuxPlatform(Arch arch, ProcessRunner runner, FileOps files, OperatorPrompt prompt) {
    this(arch, runner, files, prompt, Optional.empty(), TomcatProcesses.INSTANCE);
  }

  private LinuxPlatform(
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
    return new LinuxPlatform(
        arch(), processes(), files(), prompt(), Optional.of(installDir), tomcats());
  }

  @Override
  public OsFamily os() {
    return OsFamily.LINUX;
  }

  @Override
  public Path defaultHome() {
    return homeOrFallback(Path.of("/var/lib"));
  }

  @Override
  List<Path> wellKnownInstallDirs() {
    List<Path> candidates = new ArrayList<>();
    candidates.addAll(glob(Path.of("/opt"), "jasperreports-server*"));
    candidates.addAll(glob(Path.of("/opt/jaspersoft"), "*"));
    candidates.addAll(glob(Path.of("/usr/local"), "jasperreports-server*"));
    for (Path home : glob(Path.of("/home"), "*")) {
      candidates.addAll(glob(home, "jasperreports-server*"));
    }
    return candidates;
  }
}
