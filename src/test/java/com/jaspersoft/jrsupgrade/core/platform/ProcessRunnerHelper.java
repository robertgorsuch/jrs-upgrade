package com.jaspersoft.jrsupgrade.core.platform;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * Child-process entry point for {@link DefaultProcessRunnerTest}, launched with {@code java -cp
 * <test-classes> ProcessRunnerHelper <mode>}. Modes: {@code lines} interleaves stdout and stderr,
 * {@code sleep} prints one line then sleeps a minute, {@code env} echoes an environment variable
 * and the working directory, {@code exit} terminates with code 3.
 */
public final class ProcessRunnerHelper {

  private ProcessRunnerHelper() {}

  public static void main(String[] args) throws Exception {
    PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
    PrintStream err = new PrintStream(System.err, true, StandardCharsets.UTF_8);
    String mode = args.length > 0 ? args[0] : "lines";
    switch (mode) {
      case "lines" -> {
        for (int i = 1; i <= 3; i++) {
          out.println("out-" + i);
          Thread.sleep(20);
          err.println("err-" + i);
          Thread.sleep(20);
        }
      }
      case "sleep" -> {
        out.println("sleeping");
        Thread.sleep(60_000);
      }
      case "env" -> {
        out.println("JRS_UPGRADE_TEST=" + System.getenv("JRS_UPGRADE_TEST"));
        out.println("cwd=" + System.getProperty("user.dir"));
      }
      case "exit" -> {
        err.println("failing on purpose");
        System.exit(3);
      }
      default -> throw new IllegalArgumentException(mode);
    }
  }
}
