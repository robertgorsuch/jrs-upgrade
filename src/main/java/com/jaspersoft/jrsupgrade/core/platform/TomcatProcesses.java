package com.jaspersoft.jrsupgrade.core.platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * {@link TomcatProcessFinder} over {@link ProcessHandle#allProcesses()}, for Linux, where the JDK
 * reads command lines from {@code /proc}. Invariants: the scan never throws (processes the current
 * user cannot inspect are skipped); {@code catalina.home} and {@code catalina.base} are read from
 * {@code -D} arguments; the working directory is only known on Linux, from {@code /proc/<pid>/cwd},
 * and only for processes of the same user. On Windows the JDK returns no command line for any
 * process, so Windows uses {@link WindowsTomcatProcesses} instead (issue #38); {@link #describe} is
 * the recognition rule both share.
 */
final class TomcatProcesses implements TomcatProcessFinder {

  static final TomcatProcesses INSTANCE = new TomcatProcesses();

  private static final Pattern CATALINA_PROPERTY =
      Pattern.compile("-Dcatalina\\.(home|base)=(?:\"([^\"]+)\"|(\\S+))");
  private static final Pattern SERVICE_WRAPPER = Pattern.compile("(?i)tomcat\\d*w?\\.exe$");
  private static final Path PROC = Path.of("/proc");

  private TomcatProcesses() {}

  @Override
  public List<TomcatProcess> find() {
    long self = ProcessHandle.current().pid();
    List<TomcatProcess> found = new ArrayList<>();
    ProcessHandle.allProcesses()
        .filter(h -> h.pid() != self)
        .forEach(h -> describe(h).ifPresent(found::add));
    return List.copyOf(found);
  }

  private static Optional<TomcatProcess> describe(ProcessHandle handle) {
    ProcessHandle.Info info = handle.info();
    return describe(handle.pid(), commandLine(info), info.command().orElse(""))
        .map(
            t ->
                new TomcatProcess(
                    t.pid(),
                    t.commandLine(),
                    t.catalinaHome(),
                    t.catalinaBase(),
                    workingDir(handle.pid())));
  }

  /**
   * The Tomcat recognised in one process, or empty when it is not a Tomcat: {@code catalina} in the
   * command line, or a {@code tomcatN.exe} service wrapper, whose home is two levels above it.
   */
  static Optional<TomcatProcess> describe(long pid, String commandLine, String command) {
    boolean isTomcat =
        commandLine.toLowerCase(Locale.ROOT).contains("catalina")
            || SERVICE_WRAPPER.matcher(command).find();
    if (!isTomcat) {
      return Optional.empty();
    }
    Optional<Path> home = Optional.empty();
    Optional<Path> base = Optional.empty();
    Matcher m = CATALINA_PROPERTY.matcher(commandLine);
    while (m.find()) {
      String value = m.group(2) != null ? m.group(2) : m.group(3);
      Optional<Path> path = parsePath(value);
      if (m.group(1).equals("home")) {
        home = path;
      } else {
        base = path;
      }
    }
    if (home.isEmpty() && SERVICE_WRAPPER.matcher(command).find()) {
      // procrun lives in <tomcat>/bin/tomcat9.exe
      home = parsePath(command).map(Path::getParent).map(Path::getParent);
    }
    return Optional.of(new TomcatProcess(pid, commandLine, home, base, Optional.empty()));
  }

  private static String commandLine(ProcessHandle.Info info) {
    Optional<String> full = info.commandLine();
    if (full.isPresent()) {
      return full.get();
    }
    String command = info.command().orElse("");
    String args =
        info.arguments()
            .map(a -> java.util.Arrays.stream(a).collect(Collectors.joining(" ")))
            .orElse("");
    return args.isEmpty() ? command : command + " " + args;
  }

  private static Optional<Path> parsePath(String value) {
    try {
      return Optional.of(Path.of(value).toAbsolutePath().normalize());
    } catch (InvalidPathException e) {
      return Optional.empty();
    }
  }

  private static Optional<Path> workingDir(long pid) {
    Path link = PROC.resolve(Long.toString(pid)).resolve("cwd");
    if (!Files.isDirectory(PROC)) {
      return Optional.empty();
    }
    try {
      return Optional.of(Files.readSymbolicLink(link));
    } catch (IOException | UnsupportedOperationException | SecurityException e) {
      return Optional.empty();
    }
  }
}
