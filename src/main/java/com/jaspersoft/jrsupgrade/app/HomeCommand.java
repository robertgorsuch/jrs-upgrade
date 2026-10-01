package com.jaspersoft.jrsupgrade.app;

import com.jaspersoft.jrsupgrade.core.JrsUpgradeHome;
import com.jaspersoft.jrsupgrade.core.platform.DefaultHome;
import com.jaspersoft.jrsupgrade.core.platform.DiskSpace;
import com.jaspersoft.jrsupgrade.core.platform.HomeRedirect;
import com.jaspersoft.jrsupgrade.core.platform.Platforms;
import com.jaspersoft.jrsupgrade.core.platform.UserPaths;
import com.jaspersoft.jrsupgrade.core.state.StateStore;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.stream.Stream;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * {@code jrs-upgrade home show|set|reset} (field test 3, ADR-0041): where jrs-upgrade keeps its
 * state, and pointing it at a bigger volume. Invariants: the redirect lives in the home that would
 * otherwise be used ({@code --home}, else {@code JRS_UPGRADE_HOME}, else the platform default), so
 * every operator who reaches that home follows it; nothing here needs a configuration, a server or
 * the state store of the new home, so it works on a host that has run out of space; {@code set} and
 * {@code reset} refuse, unless {@code --force}, while the home being left still holds what a later
 * rollback or recovery needs (registered customizations, runs pending recovery), because the state
 * store records those by absolute path and they would stay behind; the redirect file is replaced
 * atomically where the file system allows it.
 */
@Command(
    name = "home",
    mixinStandardHelpOptions = true,
    exitCodeOnInvalidInput = ExitCodes.USAGE,
    description =
        "Show where jrs-upgrade keeps its state, backups and logs, and move it to another directory.",
    subcommands = {HomeCommand.Show.class, HomeCommand.Set.class, HomeCommand.Reset.class})
final class HomeCommand implements Runnable {

  @Spec CommandSpec spec;

  @Override
  public void run() {
    spec.commandLine().usage(spec.commandLine().getOut());
  }

  /** Where the home is and how it was reached. */
  record Where(Path base, String source, Optional<Path> redirect, Path home) {}

  static Where where(GlobalOptions global, Map<String, String> env) {
    Path base;
    String source;
    if (global.home().isPresent()) {
      base = global.home().get().toAbsolutePath().normalize();
      source = "--home";
    } else {
      String fromEnv = env.get("JRS_UPGRADE_HOME");
      if (fromEnv != null && !fromEnv.isBlank()) {
        base = Path.of(UserPaths.expand(fromEnv.strip(), env)).toAbsolutePath().normalize();
        source = "JRS_UPGRADE_HOME";
      } else {
        DefaultHome.Choice choice = DefaultHome.choose(env);
        // review of #169: when the system home exists but this user cannot write it, the shared
        // home is the system one, and that is where a redirect must be read or written
        base =
            (choice.systemHomeUnwritable() ? choice.systemHome() : choice.home())
                .toAbsolutePath()
                .normalize();
        source = "default";
      }
    }
    Optional<Path> redirect = HomeRedirect.target(base);
    return new Where(base, source, redirect, redirect.orElse(base));
  }

  /** {@code jrs-upgrade home show [--json]}. */
  @Command(
      name = "show",
      mixinStandardHelpOptions = true,
      exitCodeOnInvalidInput = ExitCodes.USAGE,
      description = "Show the jrs-upgrade home, why it is there, its free space and what uses it.")
  static final class Show implements Callable<Integer> {

    @Spec CommandSpec spec;
    @Mixin GlobalOptions global;

    @Override
    public Integer call() {
      PrintWriter out = spec.commandLine().getOut();
      Where w = where(global, Env.vars());
      long free = free(w.home());
      Map<String, Long> usage = usage(w.home());
      if (global.json()) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("home", w.home().toString());
        doc.put("source", w.source());
        doc.put("base", w.base().toString());
        w.redirect()
            .ifPresent(r -> doc.put("redirectFile", HomeRedirect.file(w.base()).toString()));
        doc.put("freeBytes", free);
        doc.put("usage", usage);
        JsonOut.print(out, doc);
        return ExitCodes.SUCCESS;
      }
      out.println("jrs-upgrade home: " + w.home());
      out.println(
          "  chosen by: "
              + switch (w.source()) {
                case "--home" -> "--home";
                case "JRS_UPGRADE_HOME" -> "the JRS_UPGRADE_HOME environment variable";
                default -> "the platform default";
              }
              + w.redirect().map(r -> ", redirected by " + HomeRedirect.file(w.base())).orElse(""));
      out.println("  free space: " + (free < 0 ? "unknown" : DiskSpace.human(free)));
      long total = usage.values().stream().mapToLong(Long::longValue).sum();
      out.println(
          "  used: "
              + DiskSpace.human(total)
              + " (snapshots "
              + DiskSpace.human(usage.get("snapshots"))
              + ", runs "
              + DiskSpace.human(usage.get("runs"))
              + ", logs "
              + DiskSpace.human(usage.get("logs"))
              + ", other "
              + DiskSpace.human(usage.get("other"))
              + ")");
      out.println();
      out.println("Move it to a bigger volume with: jrs-upgrade home set <dir>");
      out.println(
          "Remove old snapshots with: jrs-upgrade runs prune --dry-run, then jrs-upgrade runs prune");
      out.flush();
      return ExitCodes.SUCCESS;
    }
  }

  /** {@code jrs-upgrade home set <dir> [--force] [--json]}. */
  @Command(
      name = "set",
      mixinStandardHelpOptions = true,
      exitCodeOnInvalidInput = ExitCodes.USAGE,
      description =
          "Keep jrs-upgrade's state, backups and logs in <dir> from now on, for everyone who uses this"
              + " home (a redirect file is written in the current home).")
  static final class Set implements Callable<Integer> {

    @Spec CommandSpec spec;
    @Mixin GlobalOptions global;

    @Parameters(index = "0", paramLabel = "<dir>", description = "The new jrs-upgrade home.")
    Path dir;

    @Option(
        names = "--force",
        description =
            "Move even though the current home still holds registered customizations or runs"
                + " pending recovery (they stay behind).")
    boolean force;

    @Override
    public Integer call() {
      PrintWriter out = spec.commandLine().getOut();
      PrintWriter err = spec.commandLine().getErr();
      Where w = where(global, Env.vars());
      Path target = dir.toAbsolutePath().normalize();
      if (target.equals(w.base()) || target.equals(w.home())) {
        return ExitCodes.fail(
            out,
            err,
            global.json(),
            ExitCodes.USAGE,
            target + " is already the jrs-upgrade home",
            Optional.of("name another directory, or use jrs-upgrade home reset to undo a move"));
      }
      if (target.startsWith(w.home())
          || w.home().startsWith(target)
          || target.startsWith(w.base())
          || w.base().startsWith(target)) {
        return ExitCodes.fail(
            out,
            err,
            global.json(),
            ExitCodes.USAGE,
            target
                + " is inside the current home "
                + w.home()
                + " or the home that redirects to it ("
                + w.base()
                + "), or contains one of them",
            Optional.of("choose a directory outside it"));
      }
      if (HomeRedirect.target(target).isPresent()) {
        return ExitCodes.fail(
            out,
            err,
            global.json(),
            ExitCodes.PRECHECK_FAILED,
            target + " is itself redirected elsewhere; jrs-upgrade follows one redirect only",
            Optional.of("name the final directory, or remove " + HomeRedirect.file(target)));
      }
      Optional<String> kept = stranded(w.home());
      if (kept.isPresent() && !force) {
        return ExitCodes.fail(
            out,
            err,
            global.json(),
            ExitCodes.PRECHECK_FAILED,
            w.home() + " still holds " + kept.get() + ", which would stay behind",
            Optional.of(
                "unregister or finish them first (jrs-upgrade customizations list, jrs-upgrade"
                    + " runs list), or pass --force to move anyway; their snapshots stay in "
                    + w.home()));
      }
      try {
        Files.createDirectories(target);
        Path probe = Files.createTempFile(target, ".jrs-upgrade-probe", ".tmp");
        Files.delete(probe);
      } catch (IOException | SecurityException e) {
        return ExitCodes.fail(
            out,
            err,
            global.json(),
            ExitCodes.PRECHECK_FAILED,
            "cannot create or write " + target + ": " + e.getMessage(),
            Optional.of("choose a directory this account can write, or create it first"));
      }
      try {
        writeRedirect(w.base(), target);
      } catch (IOException e) {
        return ExitCodes.fail(
            out,
            err,
            global.json(),
            ExitCodes.PRECHECK_FAILED,
            "cannot write " + HomeRedirect.file(w.base()) + ": " + e.getMessage(),
            Optional.of(
                "run as an account that can write "
                    + w.base()
                    + " (elevated on Windows, root on Linux for the system home)"));
      }
      return report(out, global, w, target, kept.isPresent());
    }
  }

  /** {@code jrs-upgrade home reset [--force] [--json]}. */
  @Command(
      name = "reset",
      mixinStandardHelpOptions = true,
      exitCodeOnInvalidInput = ExitCodes.USAGE,
      description = "Undo jrs-upgrade home set: use the home the redirect was written in again.")
  static final class Reset implements Callable<Integer> {

    @Spec CommandSpec spec;
    @Mixin GlobalOptions global;

    @Option(
        names = "--force",
        description =
            "Go back even though the redirected home holds registered customizations or runs"
                + " pending recovery (they stay behind).")
    boolean force;

    @Override
    public Integer call() {
      PrintWriter out = spec.commandLine().getOut();
      PrintWriter err = spec.commandLine().getErr();
      Where w = where(global, Env.vars());
      if (w.redirect().isEmpty()) {
        if (global.json()) {
          return report(out, global, w, w.base(), false);
        }
        out.println("jrs-upgrade home " + w.base() + " is not redirected; nothing changed");
        out.flush();
        return ExitCodes.SUCCESS;
      }
      Optional<String> kept = stranded(w.home());
      if (kept.isPresent() && !force) {
        return ExitCodes.fail(
            out,
            err,
            global.json(),
            ExitCodes.PRECHECK_FAILED,
            w.home() + " still holds " + kept.get() + ", which would stay behind",
            Optional.of("roll back or finish them first, or pass --force"));
      }
      try {
        Files.deleteIfExists(HomeRedirect.file(w.base()));
      } catch (IOException e) {
        return ExitCodes.fail(
            out,
            err,
            global.json(),
            ExitCodes.PRECHECK_FAILED,
            "cannot remove " + HomeRedirect.file(w.base()) + ": " + e.getMessage(),
            Optional.of("run as an account that can write " + w.base()));
      }
      return report(out, global, w, w.base(), kept.isPresent());
    }
  }

  private static int report(
      PrintWriter out, GlobalOptions global, Where w, Path now, boolean leftBehind) {
    if (global.json()) {
      Map<String, Object> doc = new LinkedHashMap<>();
      doc.put("home", now.toString());
      doc.put("previous", w.home().toString());
      doc.put("base", w.base().toString());
      doc.put("redirectFile", HomeRedirect.file(w.base()).toString());
      doc.put("leftBehind", leftBehind);
      JsonOut.print(out, doc);
      return ExitCodes.SUCCESS;
    }
    out.println("jrs-upgrade home: " + now + " (was " + w.home() + ")");
    out.println(
        now.equals(w.base())
            ? "  " + HomeRedirect.file(w.base()) + " removed"
            : "  "
                + HomeRedirect.file(w.base())
                + " points there; undo with: jrs-upgrade home reset");
    out.println(
        "  nothing was copied: "
            + w.home()
            + " keeps what is in it; delete it when you no longer"
            + " need its snapshots and logs");
    out.flush();
    return ExitCodes.SUCCESS;
  }

  /**
   * What in {@code home} a later command would still need: registered customizations (their
   * snapshots) and runs pending recovery. Hotfixes another tool recorded there are not looked at
   * (ADR-0004). Empty when the home has no state store, or none of those.
   */
  static Optional<String> stranded(Path home) {
    JrsUpgradeHome h = new JrsUpgradeHome(home);
    if (!Files.isRegularFile(h.stateDb())) {
      return Optional.empty();
    }
    List<String> parts = new ArrayList<>();
    try (StateStore store = StateStore.open(h, Clock.systemUTC())) {
      int customizations = store.customizations().size();
      int pending = store.pendingRuns().size();
      if (customizations > 0) {
        parts.add(customizations + " registered customization(s)");
      }
      if (pending > 0) {
        parts.add(pending + " run(s) pending recovery");
      }
    }
    return parts.isEmpty() ? Optional.empty() : Optional.of(String.join(", ", parts));
  }

  private static void writeRedirect(Path base, Path target) throws IOException {
    Files.createDirectories(base);
    Path file = HomeRedirect.file(base);
    Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
    Files.writeString(tmp, HomeRedirect.content(target), StandardCharsets.UTF_8);
    try {
      Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private static long free(Path home) {
    try {
      return Platforms.detect().files().freeSpaceBytes(home);
    } catch (IOException | RuntimeException e) {
      return -1;
    }
  }

  /** Bytes under the home's snapshots, runs and logs, and everything else; zeros when absent. */
  static Map<String, Long> usage(Path home) {
    JrsUpgradeHome h = new JrsUpgradeHome(home);
    Map<String, Long> usage = new LinkedHashMap<>();
    long snapshots = size(h.snapshots());
    long runs = size(h.runs());
    long logs = size(home.resolve("logs"));
    usage.put("snapshots", snapshots);
    usage.put("runs", runs);
    usage.put("logs", logs);
    usage.put("other", Math.max(0, size(home) - snapshots - runs - logs));
    return usage;
  }

  private static long size(Path dir) {
    if (!Files.isDirectory(dir)) {
      return 0;
    }
    try (Stream<Path> walk = Files.walk(dir)) {
      return walk.filter(Files::isRegularFile)
          .mapToLong(
              p -> {
                try {
                  return Files.size(p);
                } catch (IOException e) {
                  return 0;
                }
              })
          .sum();
    } catch (IOException | java.io.UncheckedIOException e) {
      return 0;
    }
  }
}
