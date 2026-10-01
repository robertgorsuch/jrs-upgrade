package com.jaspersoft.jrsupgrade.jrs.service;

import com.jaspersoft.jrsupgrade.core.platform.Platform;
import com.jaspersoft.jrsupgrade.core.platform.ProcessRunner;
import com.jaspersoft.jrsupgrade.core.platform.ServiceConfig;
import com.jaspersoft.jrsupgrade.core.platform.ServiceController;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The PostgreSQL service the bundled installer registers beside the Tomcat one ({@code
 * jasperreportsPostgreSQL} next to {@code jasperreportsTomcat}); the vendor's start order is the
 * database first, then Tomcat (installation guide 10.1 p.51, issue #113). Invariants: detection
 * lists the host's services through the same commands {@code init} uses and picks a name that
 * mentions both jasper and postgres, so a database managed by someone else is never touched; only
 * the Windows service and systemd kinds have a companion, because {@code ctlscript.sh} starts both
 * itself and a script or manual kind names no service manager; listing that cannot run yields no
 * companion rather than an error, so a start never fails over a missing {@code sc.exe} or {@code
 * systemctl}.
 */
public final class CompanionDatabase {

  /** Installation guide 10.1 p.51: the bundled database must be up before Tomcat starts. */
  public static final String VENDOR_NOTE =
      "the bundled PostgreSQL service starts before Tomcat (installation guide p.51)";

  private static final Pattern SC_SERVICE_NAME =
      Pattern.compile("^\\s*SERVICE_NAME:\\s*(\\S.*?)\\s*$");
  private static final Duration LIST_TIMEOUT = Duration.ofSeconds(20);

  private CompanionDatabase() {}

  /** The companion's name on this host, for the configured kind, when there is one. */
  public static Optional<String> find(ProcessRunner runner, ServiceConfig.Kind kind) {
    return switch (kind) {
      case WINDOWS_SERVICE -> pick(windowsServices(runner));
      case SYSTEMD -> pick(systemdUnits(runner));
      case CTLSCRIPT, CATALINA, MANUAL -> Optional.empty();
    };
  }

  /** A controller for the companion of the configured service, when the host has one. */
  public static Optional<ServiceController> controller(Platform platform, ServiceConfig cfg) {
    return find(platform.processes(), cfg.kind())
        .map(
            name ->
                platform.services(
                    new ServiceConfig(
                        cfg.kind(), Optional.of(name), Optional.empty(), cfg.stopTimeout())));
  }

  /** The first name that mentions both jasper and postgres, in the order listed. */
  static Optional<String> pick(List<String> names) {
    for (String name : names) {
      String lower = name.toLowerCase(Locale.ROOT);
      if (lower.contains("jasper") && lower.contains("postgres")) {
        return Optional.of(name);
      }
    }
    return Optional.empty();
  }

  static List<String> windowsServices(ProcessRunner runner) {
    List<String> names = new ArrayList<>();
    run(
        runner,
        List.of("sc.exe", "query", "state=", "all"),
        line -> {
          Matcher m = SC_SERVICE_NAME.matcher(line);
          if (m.matches()) {
            names.add(m.group(1));
          }
        });
    return names;
  }

  static List<String> systemdUnits(ProcessRunner runner) {
    List<String> names = new ArrayList<>();
    run(
        runner,
        List.of("systemctl", "list-units", "--type=service", "--all", "--no-legend", "--plain"),
        line -> {
          String stripped = line.strip();
          if (!stripped.isEmpty()) {
            int space = stripped.indexOf(' ');
            names.add(space < 0 ? stripped : stripped.substring(0, space));
          }
        });
    return names;
  }

  private static void run(
      ProcessRunner runner, List<String> command, java.util.function.Consumer<String> onStdout) {
    try {
      runner.run(
          new ProcessRunner.Request(command, Optional.empty(), Map.of(), LIST_TIMEOUT),
          line -> {
            if (line.stream() == ProcessRunner.OutputLine.Stream.STDOUT) {
              onStdout.accept(line.text());
            }
          });
    } catch (RuntimeException e) {
      // no listing, no companion: the start goes on as before
    }
  }
}
