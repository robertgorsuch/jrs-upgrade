package com.jaspersoft.jrsupgrade.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;

/**
 * Worked examples at the end of every command's {@code --help} (#61). Invariants: the examples live
 * here, keyed by command path, and are attached programmatically to the whole tree the way {@link
 * Explain} adds {@code --explain}, so no command class declares them; every runnable command has at
 * least one example and every example parses as the command it is listed under ({@code
 * HelpExamplesTest}); a group without examples of its own ends its help with a pointer to its
 * subcommands' help; a footer a command already declares is kept after the examples; the text is
 * static and reads nothing from the home, the configuration or the server.
 */
final class HelpExamples {

  /** One example: what it does, then the command line. */
  record Example(String what, String command) {}

  private static final Map<String, List<Example>> EXAMPLES = examples();

  private HelpExamples() {}

  /** The examples for a command path such as {@code "upgrade rollback"} ({@code ""} = root). */
  static List<Example> examples(String commandPath) {
    return EXAMPLES.getOrDefault(commandPath, List.of());
  }

  /** Every command path with examples, in the order they are documented. */
  static Map<String, List<Example>> all() {
    return EXAMPLES;
  }

  /** Attaches the examples to every command under {@code root}. */
  static CommandLine install(CommandLine root) {
    install(root, Collections.newSetFromMap(new IdentityHashMap<>()));
    return root;
  }

  private static void install(CommandLine cmd, Set<CommandSpec> seen) {
    CommandSpec spec = cmd.getCommandSpec();
    if (!seen.add(spec)) {
      return;
    }
    String path = relative(spec.qualifiedName(" "));
    if (!path.equals("help")) {
      List<String> footer = new ArrayList<>();
      List<Example> examples = examples(path);
      for (Example e : examples) {
        footer.add("  " + e.what());
        footer.add("    " + e.command());
      }
      if (hasVisibleSubcommands(cmd) && !path.isEmpty()) {
        if (!footer.isEmpty()) {
          footer.add("");
        }
        footer.add("Examples for each command: jrs-upgrade " + path + " <command> --help");
      }
      if (!footer.isEmpty()) {
        List<String> declared = List.of(spec.usageMessage().footer());
        if (!declared.isEmpty()) {
          footer.add("");
          footer.addAll(declared);
        }
        spec.usageMessage()
            .footerHeading(examples.isEmpty() ? "%n" : "%nExamples:%n")
            .footer(footer.stream().map(HelpExamples::escape).toArray(String[]::new));
      }
    }
    for (CommandLine sub : cmd.getSubcommands().values()) {
      install(sub, seen);
    }
  }

  private static boolean hasVisibleSubcommands(CommandLine cmd) {
    return cmd.getSubcommands().values().stream()
        .anyMatch(
            s ->
                !s.getCommandSpec().usageMessage().hidden()
                    && !s.getCommandSpec().name().equals("help"));
  }

  /** Footer lines are format strings; a literal percent sign must be doubled. */
  private static String escape(String line) {
    return line.replace("%", "%%");
  }

  private static String relative(String qualifiedName) {
    return qualifiedName.equals("jrs-upgrade")
        ? ""
        : qualifiedName.substring("jrs-upgrade ".length());
  }

  private static Map<String, List<Example>> examples() {
    Map<String, List<Example>> m = new LinkedHashMap<>();
    m.put(
        "",
        List.of(
            new Example(
                "First run: check the tool, then connect it to the server",
                "jrs-upgrade selfcheck"),
            new Example(
                "Detect the installation and write the configuration",
                "jrs-upgrade init --install-dir /opt/jasperreports-server-pro-10.0.0"),
            new Example("Check the server before any change", "jrs-upgrade doctor")));
    m.put(
        "selfcheck",
        List.of(
            new Example("Check the tool after unpacking a new version", "jrs-upgrade selfcheck"),
            new Example(
                "The same report as JSON, for a support ticket", "jrs-upgrade selfcheck --json")));
    m.put(
        "init",
        List.of(
            new Example(
                "Detect the installation under a directory and write config.yaml",
                "jrs-upgrade init --install-dir /opt/jasperreports-server-pro-10.0.0"),
            new Example(
                "Buildomatic lives elsewhere (another disk or a share)",
                "jrs-upgrade init --install-dir /opt/jasperreports-server-pro-10.0.0"
                    + " --buildomatic-dir /data/jrs/buildomatic"),
            new Example(
                "Show what would be written, without writing anything",
                "jrs-upgrade init --install-dir /opt/jasperreports-server-pro-10.0.0 --json"),
            new Example(
                "Write jrs-upgrade.properties instead of config.yaml",
                "jrs-upgrade init --install-dir /opt/jasperreports-server-pro-10.0.0 --format properties"),
            new Example(
                "From another machine: REST export and import only",
                "jrs-upgrade init --remote https://jrs.example.com:8443/jasperserver-pro")));
    m.put(
        "doctor",
        List.of(
            new Example("Check everything before a change; changes nothing", "jrs-upgrade doctor"),
            new Example(
                "Keep diagnosing a server version outside the compatibility matrix",
                "jrs-upgrade doctor --allow-unsupported")));
    m.put(
        "smoke",
        List.of(
            new Example(
                "Log in, list the repository, run a report, export; changes nothing",
                "jrs-upgrade smoke"),
            new Example(
                "Also create, run and delete a temporary report under /temp",
                "jrs-upgrade smoke --mutating")));
    m.put(
        "config show",
        List.of(
            new Example(
                "Show the settings in use and where each came from", "jrs-upgrade config show"),
            new Example(
                "The same as jrs-upgrade.properties lines",
                "jrs-upgrade config show --format properties"),
            new Example(
                "See the effect of a one-off override",
                "jrs-upgrade config show --set server.baseUrl=https://jrs.example.com:8443/jasperserver-pro")));
    m.put(
        "config set",
        List.of(
            new Example(
                "Point jrs-upgrade at another server address",
                "jrs-upgrade config set server.baseUrl https://jrs.example.com:8443/jasperserver-pro"),
            new Example(
                "Type the admin password (hidden) and store it encrypted",
                "jrs-upgrade config set server.auth.passwordRef"),
            new Example("Be asked for the new value", "jrs-upgrade config set service.name")));
    m.put(
        "config unset",
        List.of(
            new Example(
                "Remove a setting so its default applies",
                "jrs-upgrade config unset server.runAsUser")));
    m.put(
        "config keys",
        List.of(
            new Example(
                "List every setting, its value, where it comes from and what it does",
                "jrs-upgrade config keys")));
    m.put(
        "export",
        List.of(
            new Example(
                "Back up the whole repository; the server keeps running",
                "jrs-upgrade export --out /backups/repository.zip"),
            new Example(
                "Back up one folder",
                "jrs-upgrade export --uri /public/Samples --out /backups/samples.zip"),
            new Example(
                "Everything, with users, roles and settings; the server keeps running",
                "jrs-upgrade export --full-server --out /backups/full-server.zip"),
            new Example(
                "The same with the service stopped while the export runs",
                "jrs-upgrade export --full-server --stop-service --out /backups/full-server.zip")));
    m.put(
        "import",
        List.of(
            new Example(
                "See what an import would do; changes nothing",
                "jrs-upgrade import /backups/samples.zip --plan"),
            new Example(
                "Import, replacing resources that already exist",
                "jrs-upgrade import /backups/samples.zip --update"),
            new Example(
                "Skip the rollback copy; a failed import then cannot put back what it overwrote",
                "jrs-upgrade import /backups/samples.zip --update --no-snapshot"),
            new Example(
                "Archive from a server with another keystore",
                "jrs-upgrade import /backups/full-server.zip --source-keystore /tmp/source/.jrsks"
                    + " --source-keystore-password-ref enc:SOURCE_KEYSTORE_PASSWORD")));
    m.put(
        "upgrade",
        List.of(
            new Example(
                "Look at the plan; changes nothing",
                "jrs-upgrade upgrade --to 10.0.0 --package /opt/dist/jasperreports-server-pro-10.0.0-bin"
                    + " --plan"),
            new Example(
                "Rehearse with the vendor's own validation; changes nothing",
                "jrs-upgrade upgrade --to 10.0.0 --package /opt/dist/jasperreports-server-pro-10.0.0-bin"
                    + " --test"),
            new Example(
                "Run the upgrade (newdb: jrs-upgrade exports the repository first and can rebuild the"
                    + " database from that export)",
                "jrs-upgrade upgrade --to 10.0.0 --package /opt/dist/jasperreports-server-pro-10.0.0-bin"),
            new Example(
                "Migrate the schema in place instead; back up the database yourself first",
                "jrs-upgrade upgrade --to 10.0.0 --package /opt/dist/jasperreports-server-pro-10.0.0-bin"
                    + " --mode samedb --db-backup-confirmed")));
    m.put(
        "upgrade rollback",
        List.of(
            new Example(
                "Put the files of an upgrade back (restore the database from your backup first)",
                "jrs-upgrade upgrade rollback <run-id> --to-point B")));
    m.put(
        "customizations register",
        List.of(
            new Example(
                "Keep a changed file safe across upgrades, with the vendor's original",
                "jrs-upgrade customizations register"
                    + " /opt/jrs/apache-tomcat/webapps/jasperserver-pro/WEB-INF/applicationContext-security.xml"
                    + " --original /opt/dist/applicationContext-security.xml")));
    m.put(
        "customizations scan",
        List.of(
            new Example(
                "Find what the site changed, against the unpacked vendor distribution",
                "jrs-upgrade customizations scan --vendor /opt/dist/jasperreports-server-pro-10.0.0-bin"),
            new Example(
                "Register everything found, without asking",
                "jrs-upgrade customizations scan --vendor /opt/dist/jasperserver-pro.war --register")));
    m.put(
        "customizations unregister",
        List.of(
            new Example(
                "Stop tracking a file (the file itself is not touched)",
                "jrs-upgrade customizations unregister"
                    + " /opt/jrs/apache-tomcat/webapps/jasperserver-pro/WEB-INF/applicationContext-security.xml")));
    m.put(
        "customizations list",
        List.of(
            new Example(
                "Show tracked files and whether they changed", "jrs-upgrade customizations list")));
    m.put(
        "customizations diff",
        List.of(
            new Example(
                "Compare a tracked file with its registered copy",
                "jrs-upgrade customizations diff"
                    + " /opt/jrs/apache-tomcat/webapps/jasperserver-pro/WEB-INF/applicationContext-security.xml")));
    m.put(
        "runs list",
        List.of(
            new Example("Show recent runs and how they ended", "jrs-upgrade runs list"),
            new Example("Only the last ten", "jrs-upgrade runs list --limit 10"),
            new Example(
                "Failed or rolled-back runs", "jrs-upgrade runs list --status failed,rolled-back"),
            new Example(
                "Upgrade runs of the last week",
                "jrs-upgrade runs list --operation upgrade --since 7d")));
    m.put(
        "runs show",
        List.of(
            new Example("Show every step of one run", "jrs-upgrade runs show <run-id>"),
            new Example(
                "The same, naming the run by the end of its id", "jrs-upgrade runs show ab12"),
            new Example(
                "The same as JSON, for a support ticket",
                "jrs-upgrade runs show <run-id> --json")));
    m.put(
        "runs support-bundle",
        List.of(
            new Example(
                "Write the bundle for a support ticket",
                "jrs-upgrade runs support-bundle <run-id>"),
            new Example(
                "Into a named file, listing the entries as JSON",
                "jrs-upgrade runs support-bundle <run-id> --out bundle.zip --json")));
    m.put(
        "runs recover",
        List.of(
            new Example(
                "Finish an interrupted run from the step it stopped in",
                "jrs-upgrade runs recover <run-id> --resume"),
            new Example(
                "Undo an interrupted run instead",
                "jrs-upgrade runs recover <run-id> --rollback")));
    m.put(
        "runs prune",
        List.of(
            new Example(
                "See which old backups would be deleted", "jrs-upgrade runs prune --dry-run"),
            new Example(
                "Delete them (backups a rollback needs are always kept)",
                "jrs-upgrade runs prune")));
    m.put(
        "home show",
        List.of(
            new Example(
                "See where jrs-upgrade keeps its state and backups, and how full that volume is",
                "jrs-upgrade home show")));
    m.put(
        "home set",
        List.of(
            new Example(
                "Keep state and backups on a bigger volume from now on",
                "jrs-upgrade home set /data/jrs-upgrade")));
    m.put(
        "home reset",
        List.of(
            new Example(
                "Go back to the home the redirect was written in", "jrs-upgrade home reset")));
    m.put(
        "secrets init",
        List.of(new Example("Create the encrypted password store", "jrs-upgrade secrets init")));
    m.put(
        "secrets set",
        List.of(
            new Example(
                "Store the server password (typed, not shown); use it as enc:JRS_PASSWORD",
                "jrs-upgrade secrets set JRS_PASSWORD"),
            new Example(
                "Take the value from an environment variable",
                "jrs-upgrade secrets set JRS_DB_PASSWORD --from-env JRS_DB_PASSWORD")));
    m.put(
        "secrets remove",
        List.of(
            new Example("Delete a stored password", "jrs-upgrade secrets remove JRS_DB_PASSWORD")));
    m.put(
        "secrets list",
        List.of(
            new Example("Show the names stored (never the values)", "jrs-upgrade secrets list")));
    m.put(
        "docs",
        List.of(
            new Example("List the built-in documents", "jrs-upgrade docs"),
            new Example("Read the operator guide", "jrs-upgrade docs operator-guide"),
            new Example(
                "Page through it as plain text", "jrs-upgrade docs operator-guide --format text")));
    return Collections.unmodifiableMap(m);
  }
}
