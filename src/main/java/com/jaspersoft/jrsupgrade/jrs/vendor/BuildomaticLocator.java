package com.jaspersoft.jrsupgrade.jrs.vendor;

import com.jaspersoft.jrsupgrade.core.config.Config;
import com.jaspersoft.jrsupgrade.core.platform.Platform;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Finds {@code buildomatic/} for an installation or an upgrade package (spec §7.4, ADR-0013).
 * Invariants: read-only; script names get {@code .bat} on Windows and {@code .sh} on Linux
 * according to the {@link Platform}, never by probing both; {@code default_master.properties} is
 * parsed with {@link Properties} and stripped of every key containing {@code pass} before it is
 * handed out; an unreadable properties file yields an empty map rather than a failure, because
 * locating the tree must not depend on it. {@link #resolve} applies one fixed order: a configured
 * {@code server.buildomaticDir} wins and is never replaced by a discovered tree, even when it is
 * unreachable; then {@code <installDir>/buildomatic}; then a small set of neighbouring places,
 * where a directory only counts when it holds this platform's {@code js-ant} script and a {@code
 * default_master.properties} (an unpacked distribution has neither configured, so an upgrade
 * package next to the installation is not mistaken for it); two such matches are refused as
 * ambiguous. Discovery lists at most two directories and never walks a tree, so a slow share costs
 * a bounded number of calls.
 */
public final class BuildomaticLocator {

  public static final String DIR_NAME = "buildomatic";

  /** Name prefix of the vendor's unpacked distribution directories. */
  static final String DISTRIBUTION_PREFIX = "jasperreports-server";

  private static final Pattern SEMICOLON = Pattern.compile(";");
  private static final Pattern COLON = Pattern.compile(":");

  static final String SOURCE_CONFIGURED = "server.buildomaticDir";
  static final String SOURCE_UNDER_INSTALL =
      "inside the installation directory (server.installDir)";

  private final Platform platform;

  public BuildomaticLocator(Platform platform) {
    this.platform = Objects.requireNonNull(platform, "platform");
  }

  /** Empty when {@code installDir/buildomatic} is not a directory. */
  public Optional<Buildomatic> locate(Path installDir) {
    Objects.requireNonNull(installDir, "installDir");
    return at(installDir.resolve(DIR_NAME));
  }

  /** The tree at exactly {@code dir}; empty when it is not a directory. */
  public Optional<Buildomatic> at(Path dir) {
    Objects.requireNonNull(dir, "dir");
    if (!Files.isDirectory(dir)) {
      return Optional.empty();
    }
    String ext = scriptExtension();
    Map<String, Path> scripts = new LinkedHashMap<>();
    for (String name : Buildomatic.SCRIPT_NAMES) {
      Path script = dir.resolve(name + ext);
      if (Files.isRegularFile(script)) {
        scripts.put(name, script);
      }
    }
    Path master = dir.resolve(Buildomatic.MASTER_PROPERTIES);
    Optional<Path> masterFile =
        Files.isRegularFile(master) ? Optional.of(master) : Optional.empty();
    Map<String, String> properties = Map.of();
    if (masterFile.isPresent()) {
      try {
        properties = parseMasterProperties(master);
      } catch (IOException e) {
        properties = Map.of();
      }
    }
    return Optional.of(new Buildomatic(dir, scripts, masterFile, properties));
  }

  /** The installed tree for {@code config}, in the order the class comment gives. */
  public BuildomaticResolution resolve(Config config) {
    Objects.requireNonNull(config, "config");
    Config.Server server = config.server();
    Optional<Path> configured = server.buildomaticDir().map(BuildomaticLocator::normalise);
    if (configured.isPresent()) {
      return at(configured.get())
          .<BuildomaticResolution>map(b -> new BuildomaticResolution.Found(b, SOURCE_CONFIGURED))
          .orElseGet(
              () ->
                  new BuildomaticResolution.NotFound(
                      BuildomaticResolution.Reason.CONFIGURED_UNREACHABLE,
                      "server.buildomaticDir "
                          + configured.get()
                          + " is not a directory this account can reach",
                      "mount or reconnect the volume or share that holds it, check this account"
                          + " can read it, or correct server.buildomaticDir"));
    }
    Optional<Path> installDir = server.installDir().map(BuildomaticLocator::normalise);
    Optional<Path> tomcatDir = server.tomcatDir().map(BuildomaticLocator::normalise);
    if (installDir.isEmpty() && tomcatDir.isEmpty()) {
      return new BuildomaticResolution.NotFound(
          BuildomaticResolution.Reason.NOT_CONFIGURED,
          "neither server.buildomaticDir nor server.installDir is configured",
          "run jrs-upgrade init, or set server.buildomaticDir to the buildomatic directory");
    }
    if (installDir.isPresent()) {
      Optional<Buildomatic> under = locate(installDir.get());
      if (under.isPresent()) {
        return new BuildomaticResolution.Found(under.get(), SOURCE_UNDER_INSTALL);
      }
    }
    Map<Path, String> matches = new LinkedHashMap<>();
    for (Map.Entry<Path, String> candidate : candidates(installDir, tomcatDir).entrySet()) {
      if (looksInstalled(candidate.getKey())) {
        matches.putIfAbsent(candidate.getKey(), candidate.getValue());
      }
    }
    if (matches.size() > 1) {
      return new BuildomaticResolution.NotFound(
          BuildomaticResolution.Reason.AMBIGUOUS,
          "more than one installed buildomatic directory found: "
              + matches.keySet().stream().map(Path::toString).collect(Collectors.joining(", ")),
          "set server.buildomaticDir to the one this server was installed from");
    }
    if (matches.size() == 1) {
      Map.Entry<Path, String> only = matches.entrySet().iterator().next();
      return at(only.getKey())
          .<BuildomaticResolution>map(b -> new BuildomaticResolution.Found(b, only.getValue()))
          .orElseGet(() -> notFound(installDir, tomcatDir));
    }
    return notFound(installDir, tomcatDir);
  }

  /**
   * The Ant the vendor wrappers will run: {@code apache-ant} next to the buildomatic directory,
   * which is the only bundled location {@code bin/do-js-setup} checks (relative to the working
   * directory, which is buildomatic), else an {@code ant} launcher on the {@code PATH} of {@code
   * env}; empty when neither exists, in which case {@code js-export} and {@code js-import} fail
   * with "'ant' is not recognized" (#31). A buildomatic moved away from the installation loses the
   * bundled Ant unless it is moved with it.
   */
  public Optional<Path> ant(Buildomatic buildomatic, Map<String, String> env) {
    Objects.requireNonNull(buildomatic, "buildomatic");
    Objects.requireNonNull(env, "env");
    boolean windows = platform.os() == Platform.OsFamily.WINDOWS;
    Path parent = buildomatic.dir().getParent();
    if (parent != null) {
      Path bundled =
          parent.resolve("apache-ant").resolve("bin").resolve(windows ? "ant.bat" : "ant");
      if (Files.isRegularFile(bundled)) {
        return Optional.of(bundled);
      }
    }
    String path =
        env.entrySet().stream()
            .filter(e -> e.getKey().equalsIgnoreCase("PATH"))
            .map(Map.Entry::getValue)
            .findFirst()
            .orElse("");
    List<String> launchers = windows ? List.of("ant.bat", "ant.cmd") : List.of("ant");
    for (String entry : (windows ? SEMICOLON : COLON).split(path, -1)) {
      String dir = entry.strip();
      if (dir.length() >= 2 && dir.startsWith("\"") && dir.endsWith("\"")) {
        dir = dir.substring(1, dir.length() - 1);
      }
      if (dir.isEmpty()) {
        continue;
      }
      for (String launcher : launchers) {
        try {
          Path candidate = Path.of(dir).resolve(launcher);
          if (Files.isRegularFile(candidate)) {
            return Optional.of(candidate);
          }
        } catch (InvalidPathException e) {
          // a PATH entry that is not a valid path cannot hold Ant
        }
      }
    }
    return Optional.empty();
  }

  /** {@code .bat} on Windows, {@code .sh} on Linux. */
  public String scriptExtension() {
    return switch (platform.os()) {
      case WINDOWS -> ".bat";
      case LINUX -> ".sh";
    };
  }

  /**
   * True for a Windows UNC path ({@code \\server\share\...}, or its {@code \\?\UNC\} long form).
   * {@code cmd.exe} refuses such a path as its working directory and falls back to the Windows
   * directory, so the vendor batch wrappers are not run from it as-is.
   */
  public static boolean isUncPath(Path path) {
    String p = Objects.requireNonNull(path, "path").toString();
    if (p.regionMatches(true, 0, "\\\\?\\UNC\\", 0, 8)) {
      return true;
    }
    return p.startsWith("\\\\") && !p.startsWith("\\\\?\\") && !p.startsWith("\\\\.\\");
  }

  /** Loads a properties file and drops every key whose name contains {@code pass}. */
  public static Map<String, String> parseMasterProperties(Path file) throws IOException {
    Properties p = new Properties();
    try (InputStream in = Files.newInputStream(file)) {
      p.load(in);
    }
    Map<String, String> out = new TreeMap<>();
    for (String name : p.stringPropertyNames()) {
      if (!name.toLowerCase(Locale.ROOT).contains("pass")) {
        out.put(name, p.getProperty(name));
      }
    }
    return Map.copyOf(out);
  }

  /** Neighbouring places, most specific first, each with the rule that names it. */
  private Map<Path, String> candidates(Optional<Path> installDir, Optional<Path> tomcatDir) {
    Map<Path, String> out = new LinkedHashMap<>();
    tomcatDir
        .map(Path::getParent)
        .ifPresent(
            p ->
                out.putIfAbsent(
                    p.resolve(DIR_NAME),
                    "next to the Tomcat directory (server.tomcatDir), not inside it"));
    Optional<Path> parent = installDir.map(Path::getParent);
    parent.ifPresent(
        p ->
            out.putIfAbsent(
                p.resolve(DIR_NAME), "next to the installation directory (server.installDir)"));
    installDir.ifPresent(
        d ->
            distributions(d)
                .forEach(
                    x ->
                        out.putIfAbsent(
                            x.resolve(DIR_NAME),
                            "in the "
                                + x.getFileName()
                                + " directory inside the installation directory")));
    parent.ifPresent(
        p ->
            distributions(p)
                .forEach(
                    x ->
                        out.putIfAbsent(
                            x.resolve(DIR_NAME),
                            "in the "
                                + x.getFileName()
                                + " directory next to the installation directory")));
    return out;
  }

  /** Child directories of {@code dir} named like a vendor distribution, in name order. */
  private static List<Path> distributions(Path dir) {
    List<Path> out = new ArrayList<>();
    try (DirectoryStream<Path> children = Files.newDirectoryStream(dir, Files::isDirectory)) {
      for (Path child : children) {
        Path name = child.getFileName();
        if (name != null
            && name.toString().toLowerCase(Locale.ROOT).startsWith(DISTRIBUTION_PREFIX)) {
          out.add(child);
        }
      }
    } catch (IOException | RuntimeException e) {
      // an unlistable parent (no permission, share gone) simply contributes no candidates
      return List.of();
    }
    out.sort(null);
    return out;
  }

  private boolean looksInstalled(Path dir) {
    return Files.isDirectory(dir)
        && Files.isRegularFile(dir.resolve(Buildomatic.ANT_SCRIPT + scriptExtension()))
        && Files.isRegularFile(dir.resolve(Buildomatic.MASTER_PROPERTIES));
  }

  private static BuildomaticResolution notFound(
      Optional<Path> installDir, Optional<Path> tomcatDir) {
    List<String> searched = new ArrayList<>();
    installDir.ifPresent(d -> searched.add(d.resolve(DIR_NAME).toString()));
    tomcatDir.map(Path::getParent).ifPresent(p -> searched.add(p.resolve(DIR_NAME).toString()));
    installDir.map(Path::getParent).ifPresent(p -> searched.add(p.resolve(DIR_NAME).toString()));
    // a glob is not a legal path on Windows, so the pattern entries are text, never a Path
    installDir.ifPresent(d -> searched.add(distributionPattern(d)));
    installDir.map(Path::getParent).ifPresent(p -> searched.add(distributionPattern(p)));
    return new BuildomaticResolution.NotFound(
        BuildomaticResolution.Reason.NOT_FOUND,
        "no installed buildomatic directory found (searched "
            + searched.stream().distinct().collect(Collectors.joining(", "))
            + ")",
        "set server.buildomaticDir to the buildomatic directory; another volume, a mount point or"
            + " a network share is fine");
  }

  private static String distributionPattern(Path parent) {
    String sep = parent.getFileSystem().getSeparator();
    String base = parent.toString();
    return (base.endsWith(sep) ? base : base + sep) + DISTRIBUTION_PREFIX + "*" + sep + DIR_NAME;
  }

  private static Path normalise(Path path) {
    return path.toAbsolutePath().normalize();
  }
}
