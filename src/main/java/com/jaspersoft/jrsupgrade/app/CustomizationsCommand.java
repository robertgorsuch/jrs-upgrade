package com.jaspersoft.jrsupgrade.app;

import com.jaspersoft.jrsupgrade.core.redact.Redactor;
import com.jaspersoft.jrsupgrade.core.state.Customization;
import com.jaspersoft.jrsupgrade.ops.Services;
import com.jaspersoft.jrsupgrade.ops.customizations.CustomizationException;
import com.jaspersoft.jrsupgrade.ops.customizations.CustomizationOperations;
import com.jaspersoft.jrsupgrade.ops.customizations.DefaultCustomizationOperations;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * {@code jrs-upgrade customizations register|unregister|list|diff <path>} (spec §10.3). Invariants:
 * every subcommand is a plain function over the state store and the snapshot store (no run, no
 * lock); a refused request exits 2 with the reason and the remediation; {@code diff} exits 0 when
 * the file still matches its registered copy and 1 when it differs, mirroring {@code diff(1)}.
 */
@Command(
    name = "customizations",
    mixinStandardHelpOptions = true,
    exitCodeOnInvalidInput = ExitCodes.USAGE,
    description = "Register operator-customised files so an upgrade can re-apply or report them.",
    subcommands = {
      CustomizationsCommand.Register.class,
      CustomizationsCommand.Unregister.class,
      CustomizationsCommand.ListRegistered.class,
      CustomizationsCommand.Diff.class,
      CustomizationsCommand.ScanWebapp.class
    })
final class CustomizationsCommand implements Runnable {

  @Spec CommandSpec spec;

  @Override
  public void run() {
    spec.commandLine().usage(spec.commandLine().getOut());
  }

  static CustomizationOperations open(Services services) {
    return new DefaultCustomizationOperations(services);
  }

  static int refused(PrintWriter out, PrintWriter err, boolean json, CustomizationException e) {
    return ExitCodes.fail(
        out,
        err,
        json,
        ExitCodes.PRECHECK_FAILED,
        e.getClass().getSimpleName(),
        e.getMessage(),
        Optional.of(e.remediation()),
        Map.of());
  }

  @Command(
      name = "register",
      mixinStandardHelpOptions = true,
      exitCodeOnInvalidInput = ExitCodes.USAGE,
      description = "Snapshot a customised file and record its current hash as the original.")
  static final class Register implements Callable<Integer> {
    @Spec CommandSpec spec;
    @Mixin GlobalOptions global;

    @Parameters(index = "0", paramLabel = "<path>", description = "File under the installation.")
    Path path;

    @picocli.CommandLine.Option(
        names = "--original",
        paramLabel = "<file>",
        description =
            "The vendor's unmodified copy of the file; its hash is recorded as the original so an"
                + " upgrade can re-apply the customization automatically when the vendor did not"
                + " change the file. Default: the file's current hash.")
    Path original;

    @Override
    public Integer call() {
      PrintWriter out = spec.commandLine().getOut();
      PrintWriter err = spec.commandLine().getErr();
      try (Bootstrap boot = Bootstrap.open(global, Env.vars(), Clock.systemUTC())) {
        Customization c;
        try {
          c = open(boot.services()).register(path, Optional.ofNullable(original));
        } catch (CustomizationException e) {
          return refused(out, err, global.json(), e);
        } catch (RuntimeException e) {
          return ExitCodes.reportPlanningFailure(out, err, global.json(), e);
        }
        if (global.json()) {
          JsonOut.print(out, row(c));
        } else {
          out.println(
              Redactor.global()
                  .redact(
                      "registered " + c.path() + " (original sha256 " + c.originalSha256() + ")"));
        }
        // issue #117: a file that cannot be reconciled per file is registered, with a word of
        // warning
        open(boot.services())
            .registrationAdvice(path)
            .ifPresent(a -> err.println(Redactor.global().redact("warning: " + a)));
        out.flush();
        err.flush();
        return ExitCodes.SUCCESS;
      }
    }
  }

  @Command(
      name = "unregister",
      mixinStandardHelpOptions = true,
      exitCodeOnInvalidInput = ExitCodes.USAGE,
      description = "Forget a registered file and delete its snapshot.")
  static final class Unregister implements Callable<Integer> {
    @Spec CommandSpec spec;
    @Mixin GlobalOptions global;

    @Parameters(index = "0", paramLabel = "<path>", description = "Registered file.")
    Path path;

    @Override
    public Integer call() {
      PrintWriter out = spec.commandLine().getOut();
      PrintWriter err = spec.commandLine().getErr();
      try (Bootstrap boot = Bootstrap.open(global, Env.vars(), Clock.systemUTC())) {
        boolean removed;
        try {
          removed = open(boot.services()).unregister(path);
        } catch (CustomizationException e) {
          return refused(out, err, global.json(), e);
        } catch (RuntimeException e) {
          return ExitCodes.reportPlanningFailure(out, err, global.json(), e);
        }
        if (!removed) {
          return ExitCodes.fail(
              out, err, global.json(), ExitCodes.PRECHECK_FAILED, path + " is not registered");
        }
        if (global.json()) {
          Map<String, Object> row = new LinkedHashMap<>();
          row.put("path", path.toString());
          row.put("unregistered", true);
          JsonOut.print(out, row);
        } else {
          out.println(Redactor.global().redact("unregistered " + path));
          out.flush();
        }
        return ExitCodes.SUCCESS;
      }
    }
  }

  @Command(
      name = "list",
      mixinStandardHelpOptions = true,
      exitCodeOnInvalidInput = ExitCodes.USAGE,
      description = "List registered customizations.")
  static final class ListRegistered implements Callable<Integer> {
    @Spec CommandSpec spec;
    @Mixin GlobalOptions global;

    @Override
    public Integer call() {
      PrintWriter out = spec.commandLine().getOut();
      Redactor redactor = Redactor.global();
      try (Bootstrap boot = Bootstrap.open(global, Env.vars(), Clock.systemUTC())) {
        List<Customization> all;
        try {
          all = open(boot.services()).list();
        } catch (RuntimeException e) {
          return ExitCodes.reportPlanningFailure(
              out, spec.commandLine().getErr(), global.json(), e);
        }
        if (global.json()) {
          List<Map<String, Object>> rows = new ArrayList<>();
          for (Customization c : all) {
            rows.add(row(c));
          }
          out.println(redactor.redact(JsonOut.write(rows)));
          out.flush();
          return ExitCodes.SUCCESS;
        }
        if (all.isEmpty()) {
          out.println("no customizations registered");
          out.flush();
          return ExitCodes.SUCCESS;
        }
        Ansi ansi = Ansi.forStdout(global, Env.vars());
        TextTable table = new TextTable();
        table.row(ansi.dim("PATH"), ansi.dim("ORIGINAL SHA256"), ansi.dim("REGISTERED"));
        for (Customization c : all) {
          table.row(
              c.path().toString(),
              c.originalSha256(),
              c.registeredAt().truncatedTo(ChronoUnit.SECONDS).toString());
        }
        for (String line : table.lines()) {
          out.println(redactor.redact(line));
        }
        out.flush();
        return ExitCodes.SUCCESS;
      }
    }
  }

  @Command(
      name = "diff",
      mixinStandardHelpOptions = true,
      exitCodeOnInvalidInput = ExitCodes.USAGE,
      description =
          "Unified diff between the registered copy and the file on disk (exit 1 when they differ).")
  static final class Diff implements Callable<Integer> {
    @Spec CommandSpec spec;
    @Mixin GlobalOptions global;

    @Parameters(index = "0", paramLabel = "<path>", description = "Registered file.")
    Path path;

    @Override
    public Integer call() {
      PrintWriter out = spec.commandLine().getOut();
      PrintWriter err = spec.commandLine().getErr();
      Redactor redactor = Redactor.global();
      try (Bootstrap boot = Bootstrap.open(global, Env.vars(), Clock.systemUTC())) {
        CustomizationOperations.Diff diff;
        try {
          diff = open(boot.services()).diff(path);
        } catch (CustomizationException e) {
          return refused(out, err, global.json(), e);
        } catch (RuntimeException e) {
          return ExitCodes.reportPlanningFailure(out, err, global.json(), e);
        }
        if (global.json()) {
          Map<String, Object> tree = new LinkedHashMap<>();
          tree.put("path", diff.path().toString());
          tree.put("originalSha256", diff.originalSha256());
          tree.put("registeredSha256", diff.registeredSha256());
          tree.put("currentSha256", diff.currentSha256());
          tree.put("identical", diff.identical());
          tree.put("lines", diff.lines());
          out.println(redactor.redact(JsonOut.write(tree)));
        } else if (diff.identical()) {
          out.println(redactor.redact(diff.path() + " matches its registered copy"));
        } else {
          for (String line : diff.lines()) {
            out.println(redactor.redact(line));
          }
        }
        out.flush();
        return diff.identical() ? ExitCodes.SUCCESS : ExitCodes.USAGE;
      }
    }
  }

  /**
   * {@code jrs-upgrade customizations scan --vendor <path> [--register]} (#72): what differs
   * between the installed webapp and the vendor's copy; registers the changed and added files on
   * {@code --register}, {@code --yes}, or a yes at the prompt.
   */
  @Command(
      name = "scan",
      mixinStandardHelpOptions = true,
      exitCodeOnInvalidInput = ExitCodes.USAGE,
      description =
          "Compare the installed webapp with the vendor's copy and list the files the site"
              + " changed or added; optionally register them.")
  static final class ScanWebapp implements Callable<Integer> {
    @Spec CommandSpec spec;
    @Mixin GlobalOptions global;

    @picocli.CommandLine.Option(
        names = "--vendor",
        required = true,
        paramLabel = "<path>",
        description =
            "The vendor's untouched copy of the version the server runs: the unpacked"
                + " distribution, its webapp directory, or the .war.")
    Path vendor;

    @picocli.CommandLine.Option(
        names = "--tomcat",
        description =
            "Also list the Tomcat-side files an upgrade does not carry over by itself:"
                + " bin/setenv.*, conf/server.xml, conf/Catalina/localhost/*.xml and the lib jars"
                + " Tomcat does not ship. A list of what to carry over, not of what changed.")
    boolean tomcat;

    @picocli.CommandLine.Option(
        names = "--register",
        description = "Register every changed and added file that is not registered yet.")
    boolean register;

    @picocli.CommandLine.Option(
        names = "--target",
        paramLabel = "<path>",
        description =
            "The distribution of the version to upgrade to (unpacked, its webapp directory, or"
                + " the .war): also say what becomes of each changed and added file there, from"
                + " the matrix's rules (ADR-0003).")
    Path target;

    @picocli.CommandLine.Option(
        names = "--to",
        paramLabel = "<version>",
        description = "The target's version, when --target does not state it.")
    String to;

    @Override
    public Integer call() {
      PrintWriter out = spec.commandLine().getOut();
      PrintWriter err = spec.commandLine().getErr();
      Redactor redactor = Redactor.global();
      try (Bootstrap boot = Bootstrap.open(global, Env.vars(), Clock.systemUTC())) {
        CustomizationOperations ops = open(boot.services());
        CustomizationOperations.Scan scan;
        List<CustomizationOperations.TomcatEntry> tomcatFiles = List.of();
        Optional<CustomizationOperations.Findings> findings = Optional.empty();
        try {
          scan = ops.scan(vendor);
          if (tomcat) {
            tomcatFiles = ops.scanTomcat();
          }
          if (target != null) {
            findings = Optional.of(ops.assess(scan, target, Optional.ofNullable(to)));
          }
        } catch (CustomizationException e) {
          return refused(out, err, global.json(), e);
        } catch (RuntimeException e) {
          return ExitCodes.reportPlanningFailure(out, err, global.json(), e);
        }
        long candidates =
            scan.entries().stream()
                .filter(e -> !e.registered())
                .filter(
                    e ->
                        e.change() == CustomizationOperations.Change.CHANGED
                            || e.change() == CustomizationOperations.Change.ADDED)
                .count();
        if (!global.json()) {
          print(out, redactor, scan);
          if (tomcat) {
            printTomcat(out, redactor, tomcatFiles);
          }
          findings.ifPresent(f -> printFindings(out, redactor, f));
        }
        boolean doRegister = register || global.yes();
        if (!doRegister && !global.json() && !global.nonInteractive() && candidates > 0) {
          doRegister =
              Prompter.yes(
                  out,
                  "Register the "
                      + candidates
                      + " changed and added file(s) so upgrades carry them over? [Y/n] ",
                  true);
        }
        List<Customization> registered = List.of();
        if (doRegister && candidates > 0) {
          try {
            registered = ops.registerScan(scan);
          } catch (CustomizationException e) {
            return refused(out, err, global.json(), e);
          }
        }
        if (global.json()) {
          Map<String, Object> tree = new LinkedHashMap<>();
          tree.put("installedWebapp", scan.installedWebapp().toString());
          tree.put("vendorWebapp", scan.vendorWebapp().toString());
          List<Map<String, Object>> entries = new ArrayList<>();
          for (CustomizationOperations.ScanEntry e : scan.entries()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("path", e.relativePath());
            row.put("change", e.change().name());
            row.put(
                "registered",
                e.registered()
                    || registered.stream()
                        .anyMatch(
                            c ->
                                e.installed()
                                    .map(
                                        p ->
                                            p.toAbsolutePath()
                                                .normalize()
                                                .equals(c.path().toAbsolutePath().normalize()))
                                    .orElse(false)));
            entries.add(row);
          }
          tree.put("entries", entries);
          tree.put("registered", registered.stream().map(CustomizationsCommand::row).toList());
          if (tomcat) {
            List<Map<String, Object>> files = new ArrayList<>();
            for (CustomizationOperations.TomcatEntry t : tomcatFiles) {
              Map<String, Object> row = new LinkedHashMap<>();
              row.put("path", t.relativePath());
              row.put("kind", t.kind().name());
              row.put("registered", t.registered());
              files.add(row);
            }
            tree.put("tomcat", files);
          }
          findings.ifPresent(f -> tree.put("findings", findingsTree(f)));
          out.println(redactor.redact(JsonOut.write(tree)));
        } else if (!registered.isEmpty()) {
          out.println(
              "registered "
                  + registered.size()
                  + " customization(s); see jrs-upgrade customizations list");
        } else if (candidates > 0) {
          out.println(
              "nothing registered; register them later with: jrs-upgrade customizations scan --vendor "
                  + vendor
                  + " --register");
        }
        out.flush();
        return ExitCodes.SUCCESS;
      }
    }

    private static void printTomcat(
        PrintWriter out, Redactor redactor, List<CustomizationOperations.TomcatEntry> files) {
      out.println();
      out.println("Tomcat-side files to carry over to a new Tomcat (not compared with anything):");
      if (files.isEmpty()) {
        out.println("none found");
        return;
      }
      TextTable table = new TextTable().row("KIND", "REGISTERED", "PATH");
      for (CustomizationOperations.TomcatEntry t : files) {
        table.row(t.kind().name(), t.registered() ? "yes" : "", t.relativePath());
      }
      table.lines().forEach(l -> out.println(redactor.redact(l)));
      out.println(
          "register one with: jrs-upgrade customizations register <tomcatDir>/<path>; an upgrade then"
              + " checks it, but does not copy it to a different Tomcat (--tomcat-dir)");
    }

    private static void printFindings(
        PrintWriter out, Redactor redactor, CustomizationOperations.Findings f) {
      out.println();
      out.println(
          "Against the target "
              + f.targetWebapp()
              + " ("
              + f.sourceVersion()
              + " -> "
              + f.targetVersion()
              + "):");
      out.println("jars under WEB-INF/lib the vendor copy lacks or the site patched:");
      if (f.jars().isEmpty()) {
        out.println("  none");
      } else {
        TextTable table = new TextTable().row("VERDICT", "JAR", "WHY");
        for (CustomizationOperations.JarFinding j : f.jars()) {
          table.row(j.verdict().name(), j.jar(), j.reason());
        }
        table.lines().forEach(l -> out.println(redactor.redact("  " + l)));
      }
    }

    static Map<String, Object> findingsTree(CustomizationOperations.Findings f) {
      Map<String, Object> tree = new LinkedHashMap<>();
      tree.put("targetWebapp", f.targetWebapp().toString());
      tree.put("sourceVersion", f.sourceVersion());
      tree.put("targetVersion", f.targetVersion());
      List<Map<String, Object>> jars = new ArrayList<>();
      for (CustomizationOperations.JarFinding j : f.jars()) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("jar", j.jar());
        j.coordinates().ifPresent(c -> row.put("coordinates", c));
        row.put("verdict", j.verdict().name());
        j.targetJar().ifPresent(t -> row.put("targetJar", t));
        row.put("reason", j.reason());
        jars.add(row);
      }
      tree.put("jars", jars);
      return tree;
    }

    private static void print(
        PrintWriter out, Redactor redactor, CustomizationOperations.Scan scan) {
      out.println("installed " + scan.installedWebapp());
      out.println("vendor    " + scan.vendorWebapp());
      if (scan.entries().isEmpty()) {
        out.println("no differences: the installed webapp matches the vendor's copy");
        return;
      }
      TextTable table = new TextTable().row("CHANGE", "REGISTERED", "PATH");
      for (CustomizationOperations.ScanEntry e : scan.entries()) {
        table.row(e.change().name(), e.registered() ? "yes" : "", e.relativePath());
      }
      table.lines().forEach(l -> out.println(redactor.redact(l)));
      if (scan.entries().stream()
          .anyMatch(e -> e.relativePath().startsWith(CustomizationOperations.SCRIPTS_PREFIX))) {
        out.println("note: " + CustomizationOperations.SCRIPTS_ADVICE);
      }
      long installer =
          scan.entries().stream()
              .filter(e -> e.change() == CustomizationOperations.Change.INSTALLER)
              .count();
      if (installer > 0) {
        out.println(
            "INSTALLER files hold values the installer wrote for this site; they are not"
                + " registered as customizations");
      }
    }
  }

  static Map<String, Object> row(Customization c) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("path", c.path().toString());
    row.put("originalSha256", c.originalSha256());
    row.put("snapshotRef", c.snapshotRef());
    row.put("registeredAt", c.registeredAt());
    return row;
  }
}
