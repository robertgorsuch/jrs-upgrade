package com.jaspersoft.jrsupgrade.core.platform;

import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared behaviour of {@link WindowsPlatform} and {@link LinuxPlatform}: controller selection by
 * {@code service.kind}, Tomcat layout detection and candidate de-duplication. Invariants: {@link
 * #detectTomcat} is pure inspection (no writes) and returns empty unless a {@code jasperserver} or
 * {@code jasperserver-pro} webapp is found; candidate lists contain only existing directories, each
 * once, running-Tomcat locations first.
 */
abstract class AbstractPlatform implements Platform {

  private static final Logger LOG = LoggerFactory.getLogger(AbstractPlatform.class);
  private static final int ANCESTOR_LEVELS = 4;

  private final Arch arch;
  private final ProcessRunner runner;
  private final FileOps files;
  private final OperatorPrompt prompt;
  private final Optional<Path> installDir;
  private final TomcatProcessFinder tomcats;

  AbstractPlatform(
      Arch arch,
      ProcessRunner runner,
      FileOps files,
      OperatorPrompt prompt,
      Optional<Path> installDir,
      TomcatProcessFinder tomcats) {
    this.arch = requireNonNull(arch, "arch");
    this.runner = requireNonNull(runner, "runner");
    this.files = requireNonNull(files, "files");
    this.prompt = requireNonNull(prompt, "prompt");
    this.installDir = requireNonNull(installDir, "installDir");
    this.tomcats = requireNonNull(tomcats, "tomcats");
  }

  /** A copy that watches the Tomcat under {@code installDir} for {@code service.kind: manual}. */
  @Override
  public abstract Platform withInstallDir(Path installDir);

  @Override
  public final Arch arch() {
    return arch;
  }

  @Override
  public final FileOps files() {
    return files;
  }

  @Override
  public final ProcessRunner processes() {
    return runner;
  }

  final OperatorPrompt prompt() {
    return prompt;
  }

  final Optional<Path> installDir() {
    return installDir;
  }

  final TomcatProcessFinder tomcats() {
    return tomcats;
  }

  @Override
  public final RunningTomcats runningTomcats(Path dir) {
    return RunningTomcats.scan(tomcats, requireNonNull(dir, "dir").toAbsolutePath().normalize());
  }

  @Override
  public final ServiceController services(ServiceConfig cfg) {
    requireNonNull(cfg, "cfg");
    return switch (cfg.kind()) {
      case WINDOWS_SERVICE -> new WindowsServiceController(runner, required(cfg.name(), "name"));
      case SYSTEMD ->
          new SystemdServiceController(
              runner,
              required(cfg.name(), "name"),
              tomcats,
              installDir,
              PollingServiceController.DEFAULT_POLL_INTERVAL);
      case CTLSCRIPT, CATALINA ->
          new ScriptServiceController(
              runner,
              cfg.kind(),
              required(cfg.scriptPath(), "scriptPath"),
              tomcats,
              PollingServiceController.DEFAULT_POLL_INTERVAL,
              cfg.forceStopAfter(),
              ScriptServiceController.ProcessTerminator.FORCIBLY);
      case MANUAL ->
          new ManualServiceController(
              runner, prompt, installDir, tomcats, PollingServiceController.DEFAULT_POLL_INTERVAL);
    };
  }

  private static <T> T required(Optional<T> value, String field) {
    return value.orElseThrow(
        () -> new IllegalArgumentException("service." + field + " is required for this kind"));
  }

  @Override
  public final Optional<TomcatLayout> detectTomcat(Path installDir) {
    Path base = installDir.toAbsolutePath().normalize();
    if (!Files.isDirectory(base)) {
      return Optional.empty();
    }
    Optional<Path> tomcat = findTomcatDir(base);
    if (tomcat.isEmpty()) {
      return Optional.empty();
    }
    Path webapps = tomcat.get().resolve("webapps");
    Optional<Path> webapp =
        Stream.of("jasperserver-pro", "jasperserver")
            .map(webapps::resolve)
            .filter(Files::isDirectory)
            .findFirst();
    if (webapp.isEmpty()) {
      return Optional.empty();
    }
    Optional<Path> buildomatic = existingDir(base.resolve("buildomatic"));
    Optional<Path> javaHome =
        Stream.of("java", "jre", "jdk")
            .map(base::resolve)
            .filter(AbstractPlatform::looksLikeJavaHome)
            .findFirst();
    Optional<Integer> port = ServerXml.httpPort(tomcat.get().resolve("conf").resolve("server.xml"));
    return Optional.of(
        new TomcatLayout(
            base,
            tomcat.get(),
            webapp.get(),
            webapp.get().getFileName().toString(),
            buildomatic,
            javaHome,
            port));
  }

  private static Optional<Path> findTomcatDir(Path base) {
    if (Files.isDirectory(base.resolve("webapps"))) {
      return Optional.of(base);
    }
    List<Path> matches = new ArrayList<>();
    try (DirectoryStream<Path> children = Files.newDirectoryStream(base, Files::isDirectory)) {
      for (Path child : children) {
        String name = child.getFileName().toString().toLowerCase(Locale.ROOT);
        if ((name.startsWith("apache-tomcat") || name.startsWith("tomcat"))
            && Files.isDirectory(child.resolve("webapps"))) {
          matches.add(child);
        }
      }
    } catch (IOException e) {
      LOG.debug("cannot list {}", base, e);
    }
    // field test 3: a Tomcat holding the webapp first, then the highest version by number (a string
    // sort ranks "apache-tomcat-9" above "apache-tomcat-10"), then the first path, so a plain
    // "apache-tomcat" still wins over "tomcat" as it always has
    Comparator<Path> preference =
        Comparator.comparing(AbstractPlatform::holdsWebapp)
            .thenComparing((a, b) -> NaturalOrder.compareVersions(name(a), name(b)))
            .thenComparing(NaturalOrder.PATHS.reversed());
    return matches.stream().max(preference);
  }

  private static boolean holdsWebapp(Path tomcat) {
    Path webapps = tomcat.resolve("webapps");
    return Files.isDirectory(webapps.resolve("jasperserver-pro"))
        || Files.isDirectory(webapps.resolve("jasperserver"));
  }

  private static String name(Path p) {
    Path file = p.getFileName();
    return file == null ? "" : file.toString();
  }

  private static boolean looksLikeJavaHome(Path dir) {
    Path bin = dir.resolve("bin");
    return Files.isRegularFile(bin.resolve("java")) || Files.isRegularFile(bin.resolve("java.exe"));
  }

  private static Optional<Path> existingDir(Path dir) {
    return Files.isDirectory(dir) ? Optional.of(dir) : Optional.empty();
  }

  /** The well-known places to look after the running Tomcats, most likely first. */
  abstract List<Path> wellKnownInstallDirs();

  @Override
  public final List<Path> candidateInstallDirs() {
    return scanInstallDirs().candidates();
  }

  @Override
  public final InstallScan scanInstallDirs() {
    FromProcesses fromProcesses = installDirsFromProcesses();
    List<Path> all = new ArrayList<>(fromProcesses.dirs());
    all.addAll(wellKnownInstallDirs());
    return new InstallScan(
        existingUnique(all),
        Set.copyOf(existingUnique(fromProcesses.dirs())),
        fromProcesses.limit());
  }

  /** Install dirs of running Tomcats, and why the scan may have missed one. */
  private record FromProcesses(List<Path> dirs, Optional<String> limit) {}

  /** Install dirs of running Tomcats, derived from catalina.home/base and working directory. */
  private FromProcesses installDirsFromProcesses() {
    List<Path> found = new ArrayList<>();
    List<TomcatProcessFinder.TomcatProcess> running;
    try {
      running = tomcats.find();
    } catch (TomcatScanException e) {
      LOG.debug("no install dirs from running Tomcats: {}", e.getMessage());
      return new FromProcesses(
          found,
          Optional.of(
              "the running-process scan failed ("
                  + e.getMessage()
                  + "), so a running server may not have been seen"));
    }
    long opaque = 0;
    for (TomcatProcessFinder.TomcatProcess tomcat : running) {
      if (tomcat.opaque()) {
        opaque++;
        continue;
      }
      Stream.of(tomcat.catalinaBase(), tomcat.catalinaHome(), tomcat.workingDir())
          .flatMap(Optional::stream)
          .flatMap(p -> installDirAround(p).stream())
          .forEach(found::add);
    }
    // WindowsTomcatProcesses reports another account's JVM (a service running as LocalSystem, seen
    // without elevation) as opaque: it may be the running server, and nothing says where it lives
    Optional<String> limit =
        opaque == 0
            ? Optional.empty()
            : Optional.of(
                opaque
                    + (opaque == 1 ? " Java process" : " Java processes")
                    + " whose command line this account cannot read (not elevated), so a running"
                    + " server may not have been seen; run elevated or pass --install-dir");
    return new FromProcesses(found, limit);
  }

  private Optional<Path> installDirAround(Path start) {
    Path probe = start.toAbsolutePath().normalize();
    for (int level = 0; level <= ANCESTOR_LEVELS && probe != null; level++) {
      if (detectTomcat(probe).isPresent()) {
        return Optional.of(probe);
      }
      probe = probe.getParent();
    }
    return Optional.empty();
  }

  /** Existing directories under {@code parent} whose name matches {@code glob}. */
  static List<Path> glob(Path parent, String glob) {
    List<Path> found = new ArrayList<>();
    if (!Files.isDirectory(parent)) {
      return found;
    }
    try (DirectoryStream<Path> children = Files.newDirectoryStream(parent, glob)) {
      for (Path child : children) {
        if (Files.isDirectory(child)) {
          found.add(child);
        }
      }
    } catch (IOException e) {
      LOG.debug("cannot glob {} in {}", glob, parent, e);
    }
    // highest version first by number, not by text (field test 3)
    found.sort(NaturalOrder.PATHS.reversed());
    return found;
  }

  /** Keeps existing directories only, first occurrence wins, order preserved. */
  final List<Path> existingUnique(List<Path> candidates) {
    Map<String, Path> unique = new LinkedHashMap<>();
    for (Path candidate : candidates) {
      Path normalised = candidate.toAbsolutePath().normalize();
      if (Files.isDirectory(normalised)) {
        unique.putIfAbsent(dedupeKey(normalised), normalised);
      }
    }
    return List.copyOf(unique.values());
  }

  private String dedupeKey(Path path) {
    String key = path.toString();
    return os() == OsFamily.WINDOWS ? key.toLowerCase(Locale.ROOT) : key;
  }

  /**
   * The default home under {@code base}, delegating the whole decision to {@link DefaultHome} so
   * that the logging bootstrap and this platform can never disagree. A fallback to the operator's
   * own directory is warned about every time, because it means state and the run lock are no longer
   * shared between operators of the same installation.
   */
  final Path homeOrFallback(Path base) {
    DefaultHome.Choice choice = DefaultHome.choose(base);
    if (choice.systemHomeUnwritable()) {
      LOG.warn(
          "{} exists but is not writable by this user; {} would be used instead, with its own"
              + " state.db and run lock",
          choice.systemHome(),
          choice.home());
    } else if (choice.perUser()) {
      LOG.warn(
          "no writable {}; using the per-user home {}, which no other operator's runs share",
          choice.systemHome(),
          choice.home());
    }
    return choice.home();
  }
}
