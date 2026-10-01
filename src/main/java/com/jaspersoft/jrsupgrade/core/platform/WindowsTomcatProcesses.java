package com.jaspersoft.jrsupgrade.core.platform;

import static java.util.Objects.requireNonNull;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * {@link TomcatProcessFinder} for Windows, reading {@code Win32_Process} through Windows
 * PowerShell, because the JDK's {@link ProcessHandle.Info} returns no command line for any process
 * there (issue #38, ADR-0014), and the listening ports from {@code netstat -ano}, which takes 0.03
 * s where {@code Get-NetTCPConnection} took 0.9 s and was most of a scan (ADR-0014, amended). A
 * scan runs at every service-state query and a stop or start step makes several. Invariants:
 * PowerShell runs by absolute path with a fixed script passed as {@code -EncodedCommand}, as an
 * argument list through the {@link ProcessRunner}, so no operator input is ever interpreted; paths
 * and command lines travel Base64-encoded UTF-8, so the console code page cannot mangle them; a
 * scan that exits non-zero, times out or does not print its end marker throws {@link
 * TomcatScanException}, and so does a failure to list listening ports (netstat exiting non-zero or
 * timing out); a listener is a TCP row whose foreign address is {@code 0.0.0.0:0} or {@code
 * [::]:0}, so the localised state text is never read; a {@code java.exe} or {@code javaw.exe} whose
 * command line this account cannot read (another account's process, without elevation) is returned
 * as {@link TomcatProcess#opaque()} with the ports it listens on, which are readable without
 * elevation, so {@link TomcatState} can tell an unrelated Java service from a Tomcat on the watched
 * ports; an unreadable {@code tomcatN.exe} is left out of {@link #find()}, because service wrappers
 * belong to the service kinds, which do not use this scan, and kept as opaque only by {@link
 * #findWithServiceWrappers()}, for reports (issue #147); Tomcat recognition is {@link
 * TomcatProcesses#describe}; this JVM is never listed.
 */
final class WindowsTomcatProcesses implements TomcatProcessFinder {

  static final Duration TIMEOUT = Duration.ofSeconds(30);
  static final String END = "END";

  /**
   * The row format keeps a sixth column, the listening ports, so {@link #parse} and its callers are
   * unchanged; the script no longer lists ports (that is {@code netstat}'s job now) and prints a
   * placeholder that {@link #find} replaces.
   */
  private static final String SCRIPT =
      "$ErrorActionPreference = 'Stop'\n"
          + "$enc = [Text.Encoding]::UTF8\n"
          + "Get-CimInstance -ClassName Win32_Process -Filter \"Name = 'java.exe' OR Name ="
          + " 'javaw.exe' OR Name LIKE 'tomcat%.exe'\" | ForEach-Object {\n"
          + "  $exe = if ($_.ExecutablePath) {"
          + " [Convert]::ToBase64String($enc.GetBytes($_.ExecutablePath)) } else { '-' }\n"
          + "  $cmd = if ($_.CommandLine) {"
          + " [Convert]::ToBase64String($enc.GetBytes($_.CommandLine)) } else { '-' }\n"
          + "  'P {0} {1} {2} {3} -' -f $_.ProcessId, $_.Name, $exe, $cmd\n"
          + "}\n"
          + "'"
          + END
          + "'\n";

  private static final Pattern SPACE = Pattern.compile(" ");
  private static final Pattern SPACES = Pattern.compile("\\s+");
  private static final Set<String> FOREIGN_ANY = Set.of("0.0.0.0:0", "[::]:0", "*:0");
  private static final Pattern COMMA = Pattern.compile(",");
  private static final Pattern JVM = Pattern.compile("(?i)javaw?\\.exe");
  private static final Pattern SERVICE_WRAPPER = Pattern.compile("(?i)tomcat\\d*w?\\.exe");

  private final ProcessRunner runner;
  private final List<String> command;
  private final List<String> netstat;

  WindowsTomcatProcesses(ProcessRunner runner) {
    this(runner, System.getenv("SystemRoot"));
  }

  WindowsTomcatProcesses(ProcessRunner runner, String systemRoot) {
    this.runner = requireNonNull(runner, "runner");
    this.command = command(systemRoot);
    this.netstat = netstatCommand(systemRoot);
  }

  /** {@code netstat -ano} by absolute path: numeric, with the owning process id of every socket. */
  static List<String> netstatCommand(String systemRoot) {
    String root = systemRoot == null || systemRoot.isBlank() ? "C:\\Windows" : systemRoot.strip();
    return List.of(root + "\\System32\\netstat.exe", "-ano");
  }

  List<String> netstatCommand() {
    return netstat;
  }

  /** {@code powershell.exe} by absolute path, so a minimal {@code PATH} cannot hide it. */
  static List<String> command(String systemRoot) {
    String root = systemRoot == null || systemRoot.isBlank() ? "C:\\Windows" : systemRoot.strip();
    String encoded = Base64.getEncoder().encodeToString(SCRIPT.getBytes(StandardCharsets.UTF_16LE));
    return List.of(
        root + "\\System32\\WindowsPowerShell\\v1.0\\powershell.exe",
        "-NoLogo",
        "-NoProfile",
        "-NonInteractive",
        "-EncodedCommand",
        encoded);
  }

  List<String> command() {
    return command;
  }

  @Override
  public List<TomcatProcess> find() {
    return scan(false);
  }

  @Override
  public List<TomcatProcess> findWithServiceWrappers() {
    return scan(true);
  }

  private List<TomcatProcess> scan(boolean withServiceWrappers) {
    Map<Long, Set<Integer>> listeners = listeners();
    List<String> stdout = new ArrayList<>();
    List<String> stderr = new ArrayList<>();
    ProcessRunner.Result result;
    try {
      result =
          runner.run(
              new ProcessRunner.Request(command, Optional.empty(), Map.of(), TIMEOUT),
              line -> {
                if (line.stream() == ProcessRunner.OutputLine.Stream.STDOUT) {
                  stdout.add(line.text());
                } else if (stderr.size() < 5) {
                  stderr.add(line.text());
                }
              });
    } catch (RuntimeException e) {
      throw new TomcatScanException("cannot run the Win32_Process scan: " + e.getMessage());
    }
    if (result.timedOut()) {
      throw new TomcatScanException(
          "the Win32_Process scan did not finish within " + TIMEOUT.toSeconds() + " s");
    }
    if (result.exitCode() != 0 || stdout.stream().noneMatch(l -> l.strip().equals(END))) {
      throw new TomcatScanException(
          "the Win32_Process scan failed (exit "
              + result.exitCode()
              + ")"
              + (stderr.isEmpty() ? "" : ": " + String.join(" ", stderr).strip()));
    }
    return parse(stdout, ProcessHandle.current().pid(), withServiceWrappers).stream()
        .map(t -> t.withListeningPorts(listeners.getOrDefault(t.pid(), Set.of())))
        .toList();
  }

  /** Every listening TCP port by owning process id; a failure to list them is a failed scan. */
  private Map<Long, Set<Integer>> listeners() {
    List<String> lines = new ArrayList<>();
    ProcessRunner.Result result;
    try {
      result =
          runner.run(
              new ProcessRunner.Request(netstat, Optional.empty(), Map.of(), TIMEOUT),
              line -> {
                if (line.stream() == ProcessRunner.OutputLine.Stream.STDOUT) {
                  lines.add(line.text());
                }
              });
    } catch (RuntimeException e) {
      throw new TomcatScanException("cannot list the listening ports: " + e.getMessage());
    }
    if (result.timedOut() || result.exitCode() != 0) {
      throw new TomcatScanException(
          "listing the listening ports failed (exit "
              + result.exitCode()
              + (result.timedOut() ? ", timed out" : "")
              + ")");
    }
    return parseListeners(lines);
  }

  /**
   * The listening ports of each process in {@code netstat -ano} output. A listener is a TCP row
   * whose foreign address is {@code 0.0.0.0:0}, {@code [::]:0} or {@code *:0}: the state column is
   * localised ({@code LISTENING} is {@code ABHÖREN} on a German Windows), the addresses are not.
   */
  static Map<Long, Set<Integer>> parseListeners(List<String> lines) {
    Map<Long, Set<Integer>> byPid = new java.util.HashMap<>();
    for (String raw : lines) {
      String[] f = SPACES.split(raw.strip(), -1);
      if (f.length < 4 || !f[0].equalsIgnoreCase("TCP") || !FOREIGN_ANY.contains(f[2])) {
        continue;
      }
      try {
        long pid = Long.parseLong(f[f.length - 1]);
        int colon = f[1].lastIndexOf(':');
        int port = Integer.parseInt(f[1].substring(colon + 1));
        if (colon > 0 && port > 0 && port <= 65535) {
          byPid.computeIfAbsent(pid, k -> new TreeSet<>()).add(port);
        }
      } catch (NumberFormatException e) {
        // a row that does not parse is skipped; the rest still counts
      }
    }
    Map<Long, Set<Integer>> frozen = new java.util.HashMap<>();
    byPid.forEach((pid, ports) -> frozen.put(pid, Set.copyOf(ports)));
    return Map.copyOf(frozen);
  }

  /** The processes in the scan's output, excluding {@code self}. */
  static List<TomcatProcess> parse(List<String> lines, long self) {
    return parse(lines, self, false);
  }

  /**
   * The rows of one scan; with {@code withServiceWrappers} an unreadable {@code tomcatN.exe} is
   * kept as an opaque process instead of left out (issue #147).
   */
  static List<TomcatProcess> parse(List<String> lines, long self, boolean withServiceWrappers) {
    List<TomcatProcess> found = new ArrayList<>();
    for (String raw : lines) {
      String line = raw.strip();
      if (!line.startsWith("P ")) {
        continue;
      }
      String[] fields = SPACE.split(line, -1);
      if (fields.length != 6) {
        continue;
      }
      Set<Integer> ports = ports(fields[5]);
      long pid;
      try {
        pid = Long.parseLong(fields[1]);
      } catch (NumberFormatException e) {
        continue;
      }
      if (pid == self) {
        continue;
      }
      String name = fields[2].toLowerCase(Locale.ROOT);
      Optional<String> exe = decode(fields[3]);
      Optional<String> commandLine = decode(fields[4]);
      if (commandLine.isPresent()) {
        TomcatProcesses.describe(pid, commandLine.get(), exe.orElse(""))
            .map(t -> t.withListeningPorts(ports))
            .ifPresent(found::add);
      } else if (exe.isPresent()) {
        TomcatProcesses.describe(pid, "", exe.get())
            .map(t -> t.withListeningPorts(ports))
            .ifPresent(found::add);
      } else if (JVM.matcher(name).matches()
          || (withServiceWrappers && SERVICE_WRAPPER.matcher(name).matches())) {
        found.add(
            new TomcatProcess(
                pid, "", Optional.empty(), Optional.empty(), Optional.<Path>empty(), ports));
      }
    }
    return List.copyOf(found);
  }

  private static Set<Integer> ports(String field) {
    if (field.equals("-") || field.isBlank()) {
      return Set.of();
    }
    Set<Integer> ports = new TreeSet<>();
    for (String part : COMMA.split(field, -1)) {
      try {
        int port = Integer.parseInt(part.strip());
        if (port > 0 && port <= 65535) {
          ports.add(port);
        }
      } catch (NumberFormatException e) {
        // a malformed entry is skipped; the rest of the row still counts
      }
    }
    return Set.copyOf(ports);
  }

  private static Optional<String> decode(String field) {
    if (field.equals("-") || field.isEmpty()) {
      return Optional.empty();
    }
    try {
      String text = new String(Base64.getDecoder().decode(field), StandardCharsets.UTF_8);
      return text.isBlank() ? Optional.empty() : Optional.of(text);
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }
}
