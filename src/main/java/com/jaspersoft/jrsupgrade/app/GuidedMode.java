package com.jaspersoft.jrsupgrade.app;

import com.jaspersoft.jrsupgrade.core.config.ConfigLoader;
import com.jaspersoft.jrsupgrade.core.platform.UserPaths;
import com.jaspersoft.jrsupgrade.core.redact.Redactor;
import com.jaspersoft.jrsupgrade.jrs.api.ExportRequest;
import com.jaspersoft.jrsupgrade.jrs.strategy.Sidecar;
import com.jaspersoft.jrsupgrade.ops.StrategyFlag;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The menu {@code jrs-upgrade} offers when it is started without a command on a terminal (#71; spec
 * §17.1). Invariants: every job is carried out by running the ordinary command line through {@code
 * runner}, so plans, confirmations, the run lock and exit codes are exactly the CLI's; the command
 * line is printed before it runs, so the operator learns the CLI as they go; the global options the
 * menu was started with are passed on to every command; runs that need recovery are shown before
 * the menu; every option the vendor's documented paths need is reachable here (field test 2, I4:
 * the import options, the export scope, the upgrade mode, Tomcat, existing export and rehearsal),
 * with Enter meaning the CLI's own default; a job asks only for what it needs, re-asks for a file,
 * directory or choice that is not acceptable, and returns to the menu when the operator gives an
 * empty answer where one is required; end of input quits with exit 0; nothing is written by the
 * menu itself.
 */
final class GuidedMode {

  private final PrintWriter out;
  private final List<String> globalArgs;
  private final Function<String[], Integer> runner;
  private final Supplier<List<String>> pendingRuns;
  private final Supplier<Optional<Path>> snapshotsDir;
  private final Supplier<SettingsView> settings;

  /**
   * What the settings entry shows (review of #172): whether a configuration file exists, why it
   * could not be read when it exists but is broken, and every setting's value, already redacted, in
   * schema order.
   */
  record SettingsView(boolean exists, Optional<String> problem, Map<String, String> values) {
    SettingsView {
      Objects.requireNonNull(problem, "problem");
      values = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(values));
    }

    static SettingsView none() {
      return new SettingsView(false, Optional.empty(), Map.of());
    }
  }

  GuidedMode(
      PrintWriter out,
      List<String> globalArgs,
      Function<String[], Integer> runner,
      Supplier<List<String>> pendingRuns) {
    this(out, globalArgs, runner, pendingRuns, Optional::empty);
  }

  GuidedMode(
      PrintWriter out,
      List<String> globalArgs,
      Function<String[], Integer> runner,
      Supplier<List<String>> pendingRuns,
      Supplier<Optional<Path>> snapshotsDir) {
    this(out, globalArgs, runner, pendingRuns, snapshotsDir, SettingsView::none);
  }

  /**
   * {@code settings} gives the current value of every setting in schema order, empty when there is
   * no configuration file yet (field test 3: the settings are shown before anything is asked).
   */
  GuidedMode(
      PrintWriter out,
      List<String> globalArgs,
      Function<String[], Integer> runner,
      Supplier<List<String>> pendingRuns,
      Supplier<Optional<Path>> snapshotsDir,
      Supplier<SettingsView> settings) {
    this.out = Objects.requireNonNull(out, "out");
    this.globalArgs = List.copyOf(globalArgs);
    this.runner = Objects.requireNonNull(runner, "runner");
    this.pendingRuns = Objects.requireNonNull(pendingRuns, "pendingRuns");
    this.snapshotsDir = Objects.requireNonNull(snapshotsDir, "snapshotsDir");
    this.settings = Objects.requireNonNull(settings, "settings");
  }

  /** Shows the menu until the operator quits or input ends; returns 0. */
  int run() {
    out.println("jrs-upgrade - JasperReports Server lifecycle tool");
    List<String> pending = pendingRuns.get();
    if (!pending.isEmpty()) {
      out.println();
      out.println("! An earlier job was interrupted and must be finished or undone first:");
      for (String id : pending) {
        out.println("    jrs-upgrade runs recover " + id + " --resume     (or --rollback)");
      }
      out.println("  Choose 6 below to do this.");
    }
    while (true) {
      out.println();
      out.println("What do you want to do?");
      out.println("  1) Set up or change settings");
      out.println("  2) Check server health");
      out.println("  3) Back up content");
      out.println("  4) Restore or copy content");
      out.println("  5) Upgrade the server");
      out.println("  6) Recent jobs and recovery");
      out.println("  7) Read the documentation");
      out.println("  q) Quit");
      out.println();
      out.println(
          "Each entry runs an ordinary command and prints it first. jrs-upgrade --help lists");
      out.println("every command, and --json makes any command machine-readable.");
      Optional<String> choice = Prompter.line(out, "Choose [1-7, q]: ");
      if (choice.isEmpty() || choice.get().equalsIgnoreCase("q")) {
        return ExitCodes.SUCCESS;
      }
      switch (choice.get()) {
        case "1" -> settings();
        case "2" -> execute("doctor");
        case "3" -> backup();
        case "4" -> restore();
        case "5" -> upgrade();
        case "6" -> runs();
        case "7" -> docs();
        default -> out.println("Please type a number from 1 to 7, or q.");
      }
    }
  }

  // ---- jobs -------------------------------------------------------------------------------------

  /**
   * Field test 3: the current settings come first, numbered, so the operator sees them without
   * choosing an option and can pick one by number; with no configuration yet, detection runs.
   */
  private void settings() {
    SettingsView view = settings.get();
    if (!view.exists()) {
      out.println();
      out.println("There are no settings yet; jrs-upgrade detects the installation first.");
      detect();
      return;
    }
    if (view.problem().isPresent()) {
      // review of #172: a file that exists but cannot be read needs --force to be replaced
      out.println();
      out.println("The settings file cannot be read: " + view.problem().get());
      if (Prompter.yes(
          out, "Detect the installation again and replace the settings file? [y/N] ", false)) {
        detect(true);
      }
      return;
    }
    Map<String, String> current = view.values();
    printSettings(current);
    out.println();
    out.println("  1) Change a setting");
    out.println("  2) Detect the installation again and rewrite the settings");
    out.println("  3) Where jrs-upgrade keeps its state and backups (free space, move it)");
    switch (Prompter.line(out, "Choose [1-3]: ").orElse("")) {
      case "1" -> changeSettings(current);
      case "2" -> {
        // review of #172: init refuses to overwrite config.yaml without --force
        if (Prompter.yes(
            out,
            "This replaces the current settings file with what is detected now. Go on? [y/N] ",
            false)) {
          detect(true);
        }
      }
      case "3" -> home();
      default -> {
        // back to the menu
      }
    }
  }

  /**
   * Field test 3 (ADR-0041): the home is not a setting in config.yaml, which lives inside it, so
   * the menu shows it and offers to move it with {@code jrs-upgrade home set}.
   */
  private void home() {
    execute("home", "show");
    Optional<String> dir =
        Prompter.path(out, "Move it to (a directory on a bigger volume; Enter to keep it): ");
    if (dir.isPresent() && !dir.get().isBlank()) {
      execute("home", "set", UserPaths.expand(dir.get().strip(), Env.vars()));
    }
  }

  private void printSettings(Map<String, String> current) {
    out.println();
    out.println("Current settings (secrets are shown as references):");
    int width = String.valueOf(current.size()).length();
    int n = 1;
    for (Map.Entry<String, String> e : current.entrySet()) {
      String number = String.format(java.util.Locale.ROOT, "%" + width + "d", n++);
      out.println(
          "  "
              + number
              + ") "
              + e.getKey()
              + " = "
              + (e.getValue().isEmpty() ? "(not set)" : e.getValue()));
    }
    out.flush();
  }

  /** Enter means "search for it"; a directory that does not exist is asked for again. */
  private void detect() {
    detect(false);
  }

  /** {@code replace}: a configuration exists and the operator agreed to overwrite it. */
  private void detect(boolean replace) {
    List<String> force = replace ? List.of("--force") : List.of();
    boolean asked = false;
    while (true) {
      Optional<String> dir =
          Prompter.path(out, "Installation directory (Enter to search for it): ");
      if (dir.isEmpty()) {
        return;
      }
      if (dir.get().isEmpty()) {
        if (!asked) {
          execute(concat(List.of("init"), force));
        }
        return;
      }
      if (Files.isDirectory(local(dir.get()))) {
        execute(concat(List.of("init", "--install-dir", dir.get()), force));
        return;
      }
      out.println("  no such directory: " + dir.get() + " (press Enter to go back)");
      asked = true;
    }
  }

  /**
   * Field test 3: a setting is picked by its number or its name (Tab completes names), and its
   * current value is already on the line to edit; clearing it offers to remove the setting. A
   * password is never edited in the clear: {@code config set} asks for it hidden.
   */
  private void changeSettings(Map<String, String> initial) {
    Map<String, String> current = initial;
    Set<String> known = new ConfigLoader().knownKeys();
    while (true) {
      List<String> keys = new ArrayList<>(current.keySet());
      Optional<String> pick =
          Prompter.edit(
              out,
              "Setting to change (number or name, Tab completes; Enter to finish): ",
              "",
              keys);
      if (pick.isEmpty() || pick.get().isBlank()) {
        return;
      }
      Optional<String> key = settingByNumberOrName(pick.get().strip(), keys, known);
      if (key.isEmpty()) {
        out.println(
            "  no setting " + pick.get().strip() + "; type its number or name from the list");
        continue;
      }
      if (ConfigKeys.isSecret(key.get())) {
        execute("config", "set", key.get());
      } else {
        String old = current.getOrDefault(key.get(), "");
        // review of #172: a value the redactor masked part of is not put on the line to edit, or
        // its mask would be saved back; Enter keeps the real value
        boolean hidden = old.contains(Redactor.MASK);
        Optional<String> value =
            Prompter.edit(
                out,
                key.get()
                    + (hidden
                        ? " (current value hidden; Enter keeps it, - removes it): "
                        : " (- removes it): "),
                hidden ? "" : old,
                List.of());
        if (value.isEmpty()) {
          return;
        }
        if (value.get().equals(old) || (hidden && value.get().isEmpty())) {
          out.println("  unchanged");
          continue;
        }
        // review of #172: "-" works where the value cannot be cleared (the plain fallback maps
        // an empty answer back to the old value), and clearing it works on a JLine terminal
        if (value.get().isEmpty() || value.get().equals("-")) {
          if (Prompter.yes(out, "Remove " + key.get() + " so its default applies? [y/N] ", false)) {
            execute("config", "unset", key.get());
          }
        } else {
          execute("config", "set", key.get(), value.get());
        }
      }
      SettingsView refreshed = settings.get();
      current = refreshed.values().isEmpty() ? current : refreshed.values();
    }
  }

  /** A setting from its 1-based number in {@code keys}, or its exact name. */
  static Optional<String> settingByNumberOrName(
      String typed, List<String> keys, Set<String> known) {
    if (typed.chars().allMatch(Character::isDigit)) {
      try {
        int n = Integer.parseInt(typed);
        return n >= 1 && n <= keys.size() ? Optional.of(keys.get(n - 1)) : Optional.empty();
      } catch (NumberFormatException e) {
        return Optional.empty();
      }
    }
    return known.contains(typed) ? Optional.of(typed) : Optional.empty();
  }

  private void backup() {
    out.println();
    out.println("  1) Everything");
    out.println("  2) One folder");
    List<String> args = new ArrayList<>(List.of("export"));
    boolean fullServer = false;
    switch (Prompter.line(out, "Choose [1-2]: ").orElse("")) {
      case "1" -> {
        // field test 3: one "everything"; the only content REST leaves out is report jobs and
        // calendars, so that is the question, and it decides the tool
        if (Prompter.yes(
            out,
            "Include scheduled report jobs and calendars? They need buildomatic's js-export;"
                + " without them the server's REST export is used [y/N] ",
            false)) {
          args.add("--full-server");
          fullServer = true;
          if (Prompter.yes(
              out,
              "Stop the server while js-export runs (a copy with nothing changing, short outage)?"
                  + " [y/N] ",
              false)) {
            args.add("--stop-service");
          }
        } else {
          args.addAll(List.of("--strategy", "rest"));
        }
      }
      case "2" -> {
        Optional<String> uri = text("Folder, e.g. /public/Samples");
        if (uri.isEmpty()) {
          return;
        }
        args.addAll(List.of("--uri", uri.get()));
      }
      default -> {
        return;
      }
    }
    Optional<String> file = path("Backup file to write, e.g. /backups/repository.zip");
    if (file.isEmpty()) {
      return;
    }
    if (!fullServer) {
      // a full-server export already carries users, roles and settings
      if (Prompter.yes(out, "Include users and roles? [y/N] ", false)) {
        args.add("--users-roles");
      }
      if (Prompter.yes(out, "Include server settings? [y/N] ", false)) {
        args.add("--settings");
      }
    }
    // review of #172: js-export --everything leaves the events out (administrator guide p.266), so
    // they are asked about for every export, the full-server one included
    if (Prompter.yes(out, "Include access, audit and monitoring events? [y/N] ", false)) {
      args.addAll(List.of("--access-events", "--audit-events", "--monitoring"));
    }
    if (Prompter.yes(
        out,
        "Encrypt with the Legacy key ("
            + ExportRequest.PORTABLE_KEY_ALIAS
            + ") so another server can import it? [y/N] ",
        false)) {
      args.add("--legacy-key");
    }
    Optional<String> organization = Prompter.line(out, "Organisation (Enter for all): ");
    if (organization.isEmpty()) {
      return;
    }
    if (!organization.get().isEmpty()) {
      args.addAll(List.of("--organization", organization.get()));
    }
    args.addAll(List.of("--out", file.get()));
    execute(args.toArray(String[]::new));
  }

  private void restore() {
    Optional<String> archive = existingFile("Backup file to import");
    if (archive.isEmpty()) {
      return;
    }
    out.println(
        "jrs-upgrade first exports the resources the archive will touch (the whole repository when the"
            + " archive has no .jrs-upgrade.json beside it) as a rollback copy under "
            + snapshotsDir
                .get()
                .map(Path::toString)
                .orElse("the jrs-upgrade home's snapshots directory")
            + "; a failed import re-imports that copy. Enter keeps the default for each question.");
    List<String> args = new ArrayList<>(List.of("import", archive.get()));
    if (Prompter.yes(out, "Replace resources that already exist on this server? [y/N] ", false)) {
      args.add("--update");
    }
    // ADR-0040: the rollback copy is the default; only an explicit "n" leaves it out, and the end
    // of input stops here rather than choosing for the operator
    Optional<String> copy =
        Prompter.line(
            out,
            "Take the rollback copy first (without it a failed import cannot put back what it"
                + " overwrote)? [Y/n] ");
    if (copy.isEmpty()) {
      return;
    }
    String no = copy.get().trim().toLowerCase(Locale.ROOT);
    if (no.equals("n") || no.equals("no")) {
      args.add("--no-snapshot");
    }
    if (Prompter.yes(out, "Skip themes? [y/N] ", false)) {
      args.add("--skip-themes");
    }
    Optional<String> broken =
        choice(
            "When the archive has broken dependencies: fail, skip or include [fail]",
            "fail",
            Set.of("fail", "skip", "include"));
    if (broken.isEmpty()) {
      return;
    }
    if (!broken.get().equals("fail")) {
      args.addAll(List.of("--broken-dependencies", broken.get()));
    }
    Optional<String> strategy =
        choice(
            "Strategy: auto, rest or buildomatic [auto]",
            "auto",
            Set.of("auto", "rest", StrategyFlag.BUILDOMATIC));
    if (strategy.isEmpty()) {
      return;
    }
    if (!strategy.get().equals("auto")) {
      args.addAll(List.of("--strategy", strategy.get()));
    }
    Optional<List<String>> key = keyAlias(local(archive.get()));
    if (key.isEmpty()) {
      return;
    }
    args.addAll(key.get());
    Optional<String> organization =
        Prompter.line(out, "Import into organisation (Enter for the archive's own): ");
    if (organization.isEmpty()) {
      return;
    }
    if (!organization.get().isEmpty()) {
      args.addAll(List.of("--organization", organization.get()));
      if (Prompter.yes(
          out, "Merge when the archive's organisation id differs from it? [y/N] ", false)) {
        args.add("--merge-organization");
      }
    }
    execute(args.toArray(String[]::new));
  }

  /**
   * Field test 3: the archive's {@code .jrs-upgrade.json} says which key encrypted it, so there is
   * nothing to ask when it is there; without it the question is whether the Legacy key key was
   * used, not the alias's name. Empty when input ended.
   */
  private Optional<List<String>> keyAlias(Path archive) {
    Optional<Sidecar> sidecar;
    try {
      sidecar = Sidecar.read(Sidecar.pathFor(archive));
    } catch (java.io.IOException | RuntimeException e) {
      sidecar = Optional.empty();
    }
    if (sidecar.isPresent()) {
      Optional<String> alias = sidecar.get().flags().keyAlias();
      out.println(
          alias
              .map(
                  a ->
                      "The archive's "
                          + Sidecar.pathFor(archive).getFileName()
                          + " says it was encrypted with the key alias "
                          + a
                          + "; jrs-upgrade uses it.")
              .orElse(
                  "The archive's "
                      + Sidecar.pathFor(archive).getFileName()
                      + " says it was encrypted with its server's own key."));
      return Optional.of(List.of());
    }
    // read as typed, not through choice(): a key alias is case-sensitive
    Optional<String> answer =
        Prompter.line(
            out,
            "No .jrs-upgrade.json beside the archive. Was it exported with the Legacy key ("
                + ExportRequest.PORTABLE_KEY_ALIAS
                + ", jrs-upgrade export --legacy-key)? yes, no, or type another key alias [no]: ");
    if (answer.isEmpty()) {
      return Optional.empty();
    }
    String a = answer.get().strip();
    return switch (a.toLowerCase(Locale.ROOT)) {
      case "", "no", "n" -> Optional.of(List.of());
      case "yes", "y" -> Optional.of(List.of("--key-alias", ExportRequest.PORTABLE_KEY_ALIAS));
      default -> Optional.of(List.of("--key-alias", a));
    };
  }

  private void upgrade() {
    Optional<String> version = text("Version to upgrade to, e.g. 10.0.0");
    if (version.isEmpty()) {
      return;
    }
    Optional<String> pkg = existingDirectory("Unpacked distribution of that version");
    if (pkg.isEmpty()) {
      return;
    }
    Optional<String> mode = choice("Mode [newdb]", "newdb", Set.of("newdb", "samedb"));
    if (mode.isEmpty()) {
      return;
    }
    boolean samedb = mode.get().equals("samedb");
    List<String> args =
        new ArrayList<>(List.of("upgrade", "--to", version.get(), "--package", pkg.get()));
    if (samedb) {
      args.addAll(List.of("--mode", "samedb"));
    }
    Optional<Optional<String>> tomcat =
        optionalExistingDirectory("New Tomcat directory (Enter to keep the current one)");
    if (tomcat.isEmpty()) {
      return;
    }
    tomcat.get().ifPresent(t -> args.addAll(List.of("--tomcat-dir", t)));
    if (!samedb) {
      Optional<Optional<String>> export =
          optionalExistingFile("Export taken earlier to upgrade from (Enter to export now)");
      if (export.isEmpty()) {
        return;
      }
      if (export.get().isPresent()) {
        args.addAll(List.of("--export", export.get().get()));
        Optional<String> alias =
            Prompter.line(out, "Key alias it was encrypted with (Enter for this server's key): ");
        if (alias.isEmpty()) {
          return;
        }
        if (!alias.get().isEmpty()) {
          args.addAll(List.of("--key-alias", alias.get()));
        }
      }
      // upgrade guide 10.1 p.80: the newdb script leaves the events behind (issue #106)
      if (Prompter.yes(
          out,
          "Import the access, audit and monitoring events after buildomatic's upgrade"
              + " (js-upgrade-newdb leaves them behind)? [y/N] ",
          false)) {
        args.add("--include-events");
      }
    }
    if (Prompter.yes(
        out, "Rehearse with buildomatic's own validation first (changes nothing)? [Y/n] ", true)) {
      List<String> rehearsal = new ArrayList<>(args);
      rehearsal.add("--test");
      if (execute(rehearsal.toArray(String[]::new)) != ExitCodes.SUCCESS) {
        out.println("The rehearsal found problems; fix what it reports, then come back.");
        return;
      }
    }
    if (samedb) {
      // samedb migrates the schema in place and no export undoes that (ADR-0029)
      if (!Prompter.yes(out, "Database backed up with your database tools? [y/N] ", false)) {
        out.println(
            "Back up the repository database first: samedb changes it in place and jrs-upgrade cannot"
                + " undo that.");
        return;
      }
      args.add("--db-backup-confirmed");
      // installation guide 10.1 pp.194-199 (issue #108): the vendor's password migration
      if (Prompter.yes(
          out,
          "Migrate stored passwords to the modern format after the upgrade (js-ant"
              + " migrate-passwords, 10.1 or later; only a database restore undoes it)? [y/N] ",
          false)) {
        args.add("--migrate-passwords");
      }
    } else {
      out.println(
          "jrs-upgrade takes a full export of the repository first; upgrade rollback --restore-database"
              + " can rebuild the database from it.");
    }
    execute(args.toArray(String[]::new));
  }

  private void runs() {
    execute("runs", "list");
    List<String> pending = pendingRuns.get();
    if (pending.isEmpty()) {
      return;
    }
    String id = pending.get(0);
    out.println();
    out.println("  1) Finish job " + id + " from where it stopped");
    out.println("  2) Undo job " + id);
    switch (Prompter.line(out, "Choose [1-2]: ").orElse("")) {
      case "1" -> execute("runs", "recover", id, "--resume");
      case "2" -> execute("runs", "recover", id, "--rollback");
      default -> {
        // back to the menu
      }
    }
  }

  private void docs() {
    List<EmbeddedDocs.Doc> docs = EmbeddedDocs.list();
    if (docs.isEmpty()) {
      out.println("No documentation is embedded in this build.");
      return;
    }
    out.println();
    for (int i = 0; i < docs.size(); i++) {
      out.println("  " + (i + 1) + ") " + docs.get(i).title() + " (" + docs.get(i).name() + ")");
    }
    Optional<String> pick = Prompter.line(out, "Document [1-" + docs.size() + "]: ");
    if (pick.isEmpty() || pick.get().isEmpty()) {
      return;
    }
    try {
      int n = Integer.parseInt(pick.get());
      if (n >= 1 && n <= docs.size()) {
        execute("docs", docs.get(n - 1).name());
        return;
      }
    } catch (NumberFormatException e) {
      // falls through to the hint
    }
    out.println("Please type a number from 1 to " + docs.size() + ".");
  }

  // ---- helpers ----------------------------------------------------------------------------------

  private int execute(String... command) {
    List<String> args = new ArrayList<>(List.of(command));
    args.addAll(globalArgs);
    out.println();
    out.println("Running: jrs-upgrade " + String.join(" ", args));
    out.flush();
    int code = runner.apply(args.toArray(String[]::new));
    out.println(
        code == ExitCodes.SUCCESS
            ? "Done."
            : "Finished with exit code "
                + code
                + " (jrs-upgrade docs recovery-runbook explains it).");
    return code;
  }

  /** A non-empty answer, or empty when the operator pressed Enter or input ended. */
  private Optional<String> text(String prompt) {
    return Prompter.line(out, prompt + ": ").filter(s -> !s.isEmpty());
  }

  /** Like {@link #text}, for a prompt whose answer is a filesystem path (#102, ADR-0037). */
  private Optional<String> path(String prompt) {
    return Prompter.path(out, prompt + ": ").filter(s -> !s.isEmpty());
  }

  /**
   * One of {@code allowed}, case-insensitively; Enter gives {@code fallback}; anything else is
   * asked again; empty only at end of input.
   */
  private Optional<String> choice(String prompt, String fallback, Set<String> allowed) {
    while (true) {
      Optional<String> answer = Prompter.line(out, prompt + ": ");
      if (answer.isEmpty()) {
        return Optional.empty();
      }
      String a = answer.get().toLowerCase(Locale.ROOT);
      if (a.isEmpty()) {
        return Optional.of(fallback);
      }
      if (allowed.contains(a)) {
        return Optional.of(a);
      }
      out.println("  please answer " + String.join(", ", allowed.stream().sorted().toList()));
    }
  }

  /** The path as the command will read it: a leading {@code ~} is the operator's home. */
  private static String[] concat(List<String> a, List<String> b) {
    List<String> all = new ArrayList<>(a);
    all.addAll(b);
    return all.toArray(String[]::new);
  }

  private static Path local(String typed) {
    return Path.of(UserPaths.expand(typed, Env.vars()));
  }

  private Optional<String> existingFile(String prompt) {
    while (true) {
      Optional<String> answer = path(prompt);
      if (answer.isEmpty() || Files.isRegularFile(local(answer.get()))) {
        return answer;
      }
      out.println("  no such file: " + answer.get() + " (press Enter to go back)");
    }
  }

  private Optional<String> existingDirectory(String prompt) {
    while (true) {
      Optional<String> answer = path(prompt);
      if (answer.isEmpty() || Files.isDirectory(local(answer.get()))) {
        return answer;
      }
      out.println("  no such directory: " + answer.get() + " (press Enter to go back)");
    }
  }

  /**
   * An optional path: the outer empty is end of input, the inner empty is Enter (keep the default);
   * a path that does not exist is asked again.
   */
  private Optional<Optional<String>> optionalExistingFile(String prompt) {
    while (true) {
      Optional<String> answer = Prompter.path(out, prompt + ": ");
      if (answer.isEmpty()) {
        return Optional.empty();
      }
      if (answer.get().isEmpty()) {
        return Optional.of(Optional.empty());
      }
      if (Files.isRegularFile(local(answer.get()))) {
        return Optional.of(answer);
      }
      out.println("  no such file: " + answer.get() + " (Enter to skip)");
    }
  }

  private Optional<Optional<String>> optionalExistingDirectory(String prompt) {
    while (true) {
      Optional<String> answer = Prompter.path(out, prompt + ": ");
      if (answer.isEmpty()) {
        return Optional.empty();
      }
      if (answer.get().isEmpty()) {
        return Optional.of(Optional.empty());
      }
      if (Files.isDirectory(local(answer.get()))) {
        return Optional.of(answer);
      }
      out.println("  no such directory: " + answer.get() + " (Enter to skip)");
    }
  }
}
