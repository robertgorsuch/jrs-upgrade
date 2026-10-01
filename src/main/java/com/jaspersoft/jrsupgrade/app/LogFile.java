package com.jaspersoft.jrsupgrade.app;

import com.jaspersoft.jrsupgrade.core.platform.DefaultHome;
import com.jaspersoft.jrsupgrade.core.platform.HomeRedirect;
import com.jaspersoft.jrsupgrade.core.platform.UserPaths;
import java.nio.file.Path;
import java.util.Map;

/**
 * Decides where the JSON log goes before any logger exists (spec §5.1: everything lives under
 * {@code $JRS_UPGRADE_HOME}). Invariants: the home is derived from {@code --home}, then {@code
 * JRS_UPGRADE_HOME}, then the platform default, using only the argument list and the environment so
 * no class that owns a logger is loaded first; an explicit {@code -Djrs-upgrade.log.file} is never
 * overridden. The platform default comes from {@link DefaultHome}, the same class the running
 * {@link com.jaspersoft.jrsupgrade.core.platform.Platform} uses, so the log and the state store
 * cannot end up in different homes.
 */
public final class LogFile {

  public static final String PROPERTY = "jrs-upgrade.log.file";

  /**
   * Threshold of logback's console appender: {@code OFF} with {@code --json}, else {@code WARN}.
   */
  static final String CONSOLE_LEVEL_PROPERTY = "jrs-upgrade.log.console";

  private LogFile() {}

  static void configure(String[] args, Map<String, String> env) {
    if (System.getProperty(CONSOLE_LEVEL_PROPERTY) == null) {
      System.setProperty(CONSOLE_LEVEL_PROPERTY, consoleLevel(args));
    }
    if (System.getProperty(PROPERTY) != null) {
      return;
    }
    System.setProperty(PROPERTY, resolve(args, env).toString());
  }

  /**
   * What may reach standard error from the loggers (review 4.5): nothing in {@code --json} mode,
   * where standard output carries exactly the documents and standard error must stay silent;
   * warnings and errors otherwise. INFO always goes to the JSON log file, never to the terminal.
   */
  static String consoleLevel(String[] args) {
    for (String arg : args) {
      if (arg.equals("--json")) {
        return "OFF";
      }
    }
    return "WARN";
  }

  static Path resolve(String[] args, Map<String, String> env) {
    return home(args, env).resolve("logs").resolve("jrs-upgrade.log");
  }

  static Path home(String[] args, Map<String, String> env) {
    for (int i = 0; i < args.length; i++) {
      String arg = args[i];
      if (arg.equals("--home") && i + 1 < args.length) {
        return HomeRedirect.follow(Path.of(UserPaths.expand(args[i + 1], env)));
      }
      if (arg.startsWith("--home=")) {
        return HomeRedirect.follow(
            Path.of(UserPaths.expand(arg.substring("--home=".length()), env)));
      }
    }
    String fromEnv = env.get("JRS_UPGRADE_HOME");
    if (fromEnv != null && !fromEnv.isBlank()) {
      return HomeRedirect.follow(Path.of(UserPaths.expand(fromEnv.strip(), env)));
    }
    // ADR-0041: the same one-hop redirect Bootstrap follows, so logs land in the home state uses;
    // with an unwritable system home only its own redirect counts, as in JrsUpgradeHomeResolver
    DefaultHome.Choice choice = DefaultHome.choose(env);
    if (choice.systemHomeUnwritable()) {
      return HomeRedirect.target(choice.systemHome()).orElse(choice.home());
    }
    return HomeRedirect.follow(choice.home());
  }
}
