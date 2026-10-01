package com.jaspersoft.jrsupgrade.app;

import com.jaspersoft.jrsupgrade.core.Version;
import com.jaspersoft.jrsupgrade.core.platform.UserPaths;
import java.nio.file.Path;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

/**
 * Process entry point. Invariants: the JSON log location is fixed from the arguments and
 * environment before any class that owns a logger is loaded, so the very first log line lands under
 * {@code $JRS_UPGRADE_HOME/logs}; the fully configured command line (case-insensitive enums, the
 * exit code and usage-error handlers that honour {@code --json}, and {@code --explain} on every
 * command) is built by exactly one factory, {@link #commandLine()}, so tests exercise the same tree
 * an operator gets; apart from that the class only translates a picocli exit code into the process
 * exit code.
 */
public final class Main {

  private Main() {}

  public static void main(String[] args) {
    LogFile.configure(args, System.getenv());
    System.exit(run(args));
  }

  static int run(String... args) {
    LogFile.configure(args, System.getenv());
    // #160: one line per invocation, so the log says what was asked for; the file appender
    // redacts it like every other line, and secrets are never given as arguments
    LoggerFactory.getLogger(Main.class)
        .info("jrs-upgrade {} invoked: {}", Version.current().version(), String.join(" ", args));
    return commandLine().execute(args);
  }

  /** The complete command tree as the process runs it. */
  static CommandLine commandLine() {
    return Explain.install(
        HelpExamples.install(
            new CommandLine(new JrsUpgradeCommand())
                // a leading ~ means the operator's home in every Path option (field test 2, G3)
                .registerConverter(Path.class, s -> Path.of(UserPaths.expand(s, Env.vars())))
                .setCaseInsensitiveEnumValuesAllowed(true)
                // help text follows the terminal width (COLUMNS) instead of 80 always
                .setUsageHelpAutoWidth(true)
                .setExecutionExceptionHandler(new ExitCodes.Handler())
                .setParameterExceptionHandler(new ExitCodes.ParameterHandler())));
  }
}
